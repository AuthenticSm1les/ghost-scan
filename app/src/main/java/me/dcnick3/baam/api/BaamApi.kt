package me.dcnick3.baam.api

import android.util.Log
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

private const val TAG = "BaamApi"

@Serializable
data class AttendanceSession(
    val sessionCode: String,
    val title: String,
    val startDate: String,
)

sealed class BaamError {
    /**
     * Represents server (50x) and client (40x) errors.
     */
    data class HttpError(val code: HttpStatusCode) : BaamError()

    object AuthNeeded : BaamError()

    /**
     * Represent IOExceptions and connectivity issues.
     */
    data class NetworkError(val error: IOException) : BaamError()
}

typealias ApiResult<R> = Result<R, BaamError>

const val baamDomain = "baam.tatar"
val baamBaseUrl = "https://$baamDomain/"

class BaamApi internal constructor() {
    private val client = HttpClient {
        defaultRequest {
            url(baamBaseUrl)
        }
        install(Logging) {
            logger = Logger.SIMPLE
        }
        install(ContentNegotiation) {
            json(Json { isLenient = false; ignoreUnknownKeys = false })
        }
        followRedirects = false
    }

    private suspend inline fun handleCall(makeRequest: (HttpClient) -> HttpResponse): ApiResult<Unit> {
        val response = try {
            makeRequest(client)
        } catch (e: IOException) {
            return Err(BaamError.NetworkError(e))
        }

        if (response.status == HttpStatusCode.Found &&
            response.headers["Location"]?.startsWith("https://sso.university.innopolis.ru/adfs/oauth2/authorize/") == true
        ) {
            return Err(BaamError.AuthNeeded)
        }
        if (!response.status.isSuccess()) {
            Log.e(TAG, "HTTP error: ${response.status}\n${response.bodyAsText()}")
            return Err(BaamError.HttpError(response.status))
        }
        return Ok(Unit)
    }

    // This API call is only used to check the auth cookie, so it's fine to ignore the response
    suspend fun getSessions(cookie: String): ApiResult<Unit> {
        return handleCall { client ->
            client.get("api/AttendanceSession/") {
                header(HttpHeaders.Cookie, cookie)
            }
        }
    }

    suspend fun submitChallenge(cookie: String, code: String, challenge: String): ApiResult<Unit> {
        val result: ApiResult<Unit>? = withTimeoutOrNull(1000) {
            handleCall { client ->
                client.post("api/AttendanceSession/$code/submitChallenge") {
                    header(HttpHeaders.Cookie, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(JsonPrimitive(challenge))
                }
            }
        }
        return (result ?: Err(BaamError.NetworkError(IOException("Timeout"))))
    }
}

val baamApi = BaamApi()