package me.dcnick3.baam.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.http.HttpStatusCode
import java9.util.concurrent.CompletableFuture
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.dcnick3.baam.api.Account
import me.dcnick3.baam.api.AppUpdateRepository
import me.dcnick3.baam.api.BaamError
import me.dcnick3.baam.api.ParsedChallenge
import me.dcnick3.baam.api.baamApi
import me.dcnick3.baam.api.baamBaseUrl
import me.dcnick3.baam.api.baamDomain
import me.dcnick3.baam.api.extractIdentity
import me.dcnick3.baam.utils.await
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.properties.ReadOnlyProperty

private const val TAG = "ApiViewModel"

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataRepository(private val dataStore: DataStore<Preferences>) {
    // single-account versions of the app stored just the cookie
    private val legacyCookieKey: Preferences.Key<String> = stringPreferencesKey("cookie")
    private val accountsKey: Preferences.Key<String> = stringPreferencesKey("accounts")
    private val selectedAccountsKey: Preferences.Key<Set<String>> =
        stringSetPreferencesKey("selected_accounts")

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getAccounts(): List<Account> {
        val preferences = dataStore.data.first()
        val accounts = preferences[accountsKey]
        if (accounts == null) {
            val legacyCookie = preferences[legacyCookieKey] ?: return emptyList()
            return listOf(newAccount(legacyCookie, emptyList()))
        }
        return try {
            json.decodeFromString(accounts)
        } catch (e: SerializationException) {
            Log.e(TAG, "Failed to parse stored accounts", e)
            emptyList()
        }
    }

    suspend fun setAccounts(accounts: List<Account>) {
        dataStore.edit {
            it[accountsKey] = json.encodeToString(accounts)
            it.remove(legacyCookieKey)
        }
    }

    suspend fun getSelectedAccountIds(): Set<String> {
        return dataStore.data.first()[selectedAccountsKey] ?: emptySet()
    }

    suspend fun setSelectedAccountIds(ids: Set<String>) {
        dataStore.edit {
            it[selectedAccountsKey] = ids
        }
    }
}

private val Context.dataRepository: DataRepository by
ReadOnlyProperty<Context, DataRepository> { context, _ ->
    DataRepository(context.dataStore)
}

private fun newAccount(cookie: String, existing: List<Account>): Account {
    val identity = extractIdentity(cookie)
    val name = identity ?: generateSequence(1) { it + 1 }
        .map { "Account $it" }
        .first { name -> existing.none { it.name == name } }
    return Account(UUID.randomUUID().toString(), name, cookie, identity)
}

private suspend fun clearWebCookies() {
    val cookieManager = CookieManager.getInstance()
    suspendCoroutine { cont ->
        cookieManager.removeAllCookies { cont.resume(Unit) }
    }
    cookieManager.flush()
}

/**
 * Makes the web view act as the given account (used by the success page).
 */
private suspend fun setWebCookies(cookie: String) {
    clearWebCookies()
    val cookieManager = CookieManager.getInstance()
    for (pair in cookie.split(';')) {
        if (pair.isNotBlank()) {
            cookieManager.setCookie(baamBaseUrl, pair.trim())
        }
    }
    cookieManager.flush()
}

sealed class LoginState {
    /** Not logging in, the scanner is shown. */
    data object Idle : LoginState()

    /** Clearing web view cookies, so that the SSO asks for credentials again. */
    data object Preparing : LoginState()

    /** The SSO login page is shown. [attempt] changes every time the page needs to be reset. */
    data class InProgress(val attempt: Int) : LoginState()

    data class Failed(val error: String) : LoginState()
}

sealed class ChallengeResult {
    /**
     * The attendance was marked for at least one of the selected accounts.
     * [summary] has a line per account when scanning for more than one.
     */
    data class Success(val code: String, val summary: List<String>) : ChallengeResult()
    data object Unacceptable : ChallengeResult()
    data class Error(val error: String) : ChallengeResult()
}

private sealed class SubmitOutcome {
    data object Marked : SubmitOutcome()
    data object Unacceptable : SubmitOutcome()
    data class Failed(val error: String) : SubmitOutcome()

    val message
        get() = when (this) {
            Marked -> "marked"
            Unacceptable -> "code not accepted"
            is Failed -> error
        }
}

sealed class Command {
    data object LoadAccounts : Command()
    data class AddAccount(val cookie: String) : Command()
    data class SubmitChallenge(
        val challenge: ParsedChallenge,
        val fut: CompletableFuture<ChallengeResult>
    ) : Command()
}

@HiltViewModel
class ApiViewModel @Inject constructor(
    val updateRepository: AppUpdateRepository,
    application: Application
) : AndroidViewModel(application) {
    private val commandChannel = Channel<Command>(2)

    private val context get() = getApplication<Application>().applicationContext

    private val _accounts = MutableStateFlow(emptyList<Account>())
    val accounts = _accounts.asStateFlow()

    private val _selectedAccountIds = MutableStateFlow(emptySet<String>())

    /**
     * The accounts scanned codes are submitted for.
     */
    val selectedAccounts = combine(_accounts, _selectedAccountIds) { accounts, ids ->
        accounts.filter { it.id in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _loginState = MutableStateFlow(LoginState.Idle as LoginState)
    val loginState = _loginState.asStateFlow()
    private var loginAttempt = 0

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * One-off messages about accounts (e.g. an expired session) to show to the user.
     */
    val notices = _notices.asSharedFlow()

    init {
        viewModelScope.launch {
            commandChannel.send(Command.LoadAccounts)

            commandChannel.receiveAsFlow().collect { cmd ->
                when (cmd) {
                    is Command.LoadAccounts -> loadAccounts()
                    is Command.AddAccount -> validateAndAddAccount(cmd.cookie)
                    is Command.SubmitChallenge -> cmd.fut.complete(submit(cmd.challenge))
                }
            }
        }
        viewModelScope.launch {
            updateRepository.fetchUpdate()
        }
    }

    // TODO: actually, I think we can check the auth validity 100% offline
    // we use JWT so we just need to check the signature and the expiration date
    // but it's easier to just check it online for now
    private suspend fun loadAccounts() {
        val repository = context.dataRepository
        val accounts = repository.getAccounts()
        val selectedIds = repository.getSelectedAccountIds()

        _accounts.value = accounts
        _selectedAccountIds.value = accounts.map { it.id }.filter { it in selectedIds }.toSet()
            .ifEmpty { setOfNotNull(accounts.firstOrNull()?.id) }
        persistAccounts()

        if (accounts.isEmpty()) {
            startLogin()
            return
        }

        for (account in accounts) {
            when (val result = baamApi.getSessions(account.cookie)) {
                is Ok -> {}
                Err(BaamError.AuthNeeded) -> onInvalidAuth(account)
                is Err -> Log.e(TAG, "Error while checking auth of ${account.name}: ${result.error}")
            }
        }
    }

    private suspend fun validateAndAddAccount(cookie: String) {
        val error = when (val result = baamApi.getSessions(cookie)) {
            is Ok -> null
            Err(BaamError.AuthNeeded) -> "The login was not accepted by baam"
            is Err -> "Error while checking the login: ${result.error}"
        }
        if (error != null) {
            Log.e(TAG, error)
            _loginState.value = LoginState.Failed(error)
            return
        }

        Log.i(TAG, "Auth valid, storing account")
        val accounts = _accounts.value
        val account = newAccount(cookie, accounts)
        val existing = account.identity?.let { identity -> accounts.find { it.identity == identity } }
        if (existing != null) {
            // logged into the same user again, just refresh its cookie
            _accounts.value = accounts.map { if (it.id == existing.id) it.copy(cookie = cookie) else it }
            _selectedAccountIds.value += existing.id
        } else {
            _accounts.value = accounts + account
            _selectedAccountIds.value += account.id
        }
        persistAccounts()
        _loginState.value = LoginState.Idle
    }

    private suspend fun submit(challenge: ParsedChallenge): ChallengeResult {
        val accounts = selectedAccounts.value
        if (accounts.isEmpty()) {
            return ChallengeResult.Error("No account selected")
        }

        if (challenge.url.host != baamDomain) {
            Log.w(TAG, "Invalid challenge URL: ${challenge.url}")
        }

        // the challenge expires quickly, so submit for all accounts at once
        val outcomes = coroutineScope {
            accounts.map { account ->
                async { account to submitFor(account, challenge) }
            }.awaitAll()
        }

        val marked = outcomes.filter { it.second == SubmitOutcome.Marked }
        if (marked.isNotEmpty()) {
            // the success page is a web page, so it needs to see one of the marked accounts
            setWebCookies(marked.first().first.cookie)
            val summary = if (accounts.size > 1) {
                outcomes.map { (account, outcome) ->
                    if (outcome == SubmitOutcome.Marked) "✓ ${account.name}"
                    else "✗ ${account.name}: ${outcome.message}"
                }
            } else {
                emptyList()
            }
            return ChallengeResult.Success(challenge.code, summary)
        }

        if (outcomes.all { it.second == SubmitOutcome.Unacceptable }) {
            return ChallengeResult.Unacceptable
        }

        return ChallengeResult.Error(
            if (accounts.size > 1) {
                outcomes.joinToString("\n") { (account, outcome) -> "${account.name}: ${outcome.message}" }
            } else {
                outcomes.single().second.message
            }
        )
    }

    private suspend fun submitFor(account: Account, challenge: ParsedChallenge): SubmitOutcome {
        return when (val result =
            baamApi.submitChallenge(account.cookie, challenge.code, challenge.challenge)) {
            is Ok -> SubmitOutcome.Marked

            is Err -> {
                when (val error = result.error) {
                    BaamError.HttpError(HttpStatusCode.Forbidden) -> {
                        SubmitOutcome.Unacceptable
                    }

                    BaamError.AuthNeeded -> {
                        onInvalidAuth(account, notify = false)
                        SubmitOutcome.Failed("Login expired, please add the account again")
                    }

                    BaamError.HttpError(HttpStatusCode.NotFound) -> {
                        SubmitOutcome.Failed("Session not found")
                    }

                    is BaamError.HttpError -> {
                        SubmitOutcome.Failed("HTTP Error: ${error.code}")
                    }

                    is BaamError.NetworkError -> {
                        SubmitOutcome.Failed("Network error: ${error.error}")
                    }
                }
            }
        }
    }

    private fun onInvalidAuth(account: Account, notify: Boolean = true) {
        Log.i(TAG, "Auth invalid for ${account.name}, removing the account")
        if (notify) {
            _notices.tryEmit("Login expired for ${account.name}, please add it again")
        }
        removeAccount(account.id)
    }

    private fun persistAccounts() {
        viewModelScope.launch {
            // always write the latest state, so that the order of the writes doesn't matter
            context.dataRepository.setAccounts(_accounts.value)
            context.dataRepository.setSelectedAccountIds(_selectedAccountIds.value)
        }
    }

    /**
     * Shows the SSO login page to add a new account.
     */
    fun startLogin() {
        _loginState.value = LoginState.Preparing
        viewModelScope.launch {
            // otherwise the SSO (and baam) would just log us into the last account again
            clearWebCookies()
            _loginState.value = LoginState.InProgress(++loginAttempt)
        }
    }

    fun cancelLogin() {
        if (_accounts.value.isNotEmpty()) {
            _loginState.value = LoginState.Idle
        }
    }

    /**
     * Adds or removes the account from the ones codes are submitted for.
     * At least one account always stays selected.
     */
    fun toggleAccount(id: String) {
        val ids = _selectedAccountIds.value
        _selectedAccountIds.value = when {
            id !in ids -> ids + id
            ids.size > 1 -> ids - id
            else -> return
        }
        persistAccounts()
    }

    fun renameAccount(id: String, name: String) {
        _accounts.value = _accounts.value.map { if (it.id == id) it.copy(name = name) else it }
        persistAccounts()
    }

    fun removeAccount(id: String) {
        val accounts = _accounts.value.filter { it.id != id }
        _accounts.value = accounts
        _selectedAccountIds.value = (_selectedAccountIds.value - id)
            .ifEmpty { setOfNotNull(accounts.firstOrNull()?.id) }
        persistAccounts()
        if (accounts.isEmpty()) {
            startLogin()
        }
    }

    fun addAccount(cookie: String) {
        viewModelScope.launch {
            commandChannel.send(Command.AddAccount(cookie))
        }
    }

    suspend fun submitChallenge(challenge: ParsedChallenge): ChallengeResult {
        val fut = CompletableFuture<ChallengeResult>()
        viewModelScope.launch {
            commandChannel.send(Command.SubmitChallenge(challenge, fut))
        }
        return fut.await()
    }
}
