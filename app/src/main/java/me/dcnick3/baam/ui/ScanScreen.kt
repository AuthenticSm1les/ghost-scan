package me.dcnick3.baam.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import me.dcnick3.baam.ui.camera.NoPermissionScreen
import me.dcnick3.baam.ui.camera.ScannerScreen
import me.dcnick3.baam.ui.update.UpdateBar
import me.dcnick3.baam.ui.web.AuthScreen
import me.dcnick3.baam.utils.ConnectionState
import me.dcnick3.baam.utils.connectivityState
import me.dcnick3.baam.viewmodel.ApiViewModel
import me.dcnick3.baam.viewmodel.LoginState


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScanScreen(navController: NavController, vm: ApiViewModel = hiltViewModel()) {
    val cameraPermissionState: PermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val connection by connectivityState()

    LaunchedEffect(key1 = Unit) {
        if (!cameraPermissionState.status.isGranted && !cameraPermissionState.status.shouldShowRationale) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    val loginState by vm.loginState.collectAsState()
    val selectedAccounts by vm.selectedAccounts.collectAsState()
    val availableUpdate by vm.updateRepository.availableUpdateState.collectAsState()

    if (cameraPermissionState.status.isGranted) {
        if (connection == ConnectionState.Available) {
            Column(modifier = Modifier.fillMaxHeight()) {
                UpdateBar(navController, availableUpdate)
                when (val state = loginState) {
                    LoginState.Idle -> {
                        AccountBar(vm)
                        // restart the scanner when the selection changes, so that already seen codes are submitted again
                        key(selectedAccounts.map { it.id }) {
                            ScannerScreen(navController)
                        }
                    }

                    LoginState.Preparing -> {
                        LoginHeader(vm)
                    }

                    is LoginState.InProgress -> {
                        LoginHeader(vm)
                        key(state.attempt) {
                            AuthScreen()
                        }
                    }

                    is LoginState.Failed -> {
                        LoginHeader(vm)
                        LoginFailed(state.error, onRetry = vm::startLogin)
                    }
                }
            }
        } else {
            NoInternetScreen()
        }
    } else {
        // In this screen you should notify the user that the permission
        // is required and maybe offer a button to start another
        // camera permission request
        NoPermissionScreen(cameraPermissionState::launchPermissionRequest)
    }

}

@Composable
private fun LoginHeader(vm: ApiViewModel) {
    val accounts by vm.accounts.collectAsState()
    if (accounts.isEmpty()) {
        return
    }

    BackHandler(onBack = vm::cancelLogin)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Log into another account",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        TextButton(onClick = vm::cancelLogin) {
            Text("Cancel")
        }
    }
}

@Composable
private fun LoginFailed(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = error,
            textAlign = TextAlign.Center,
            fontSize = 4.em,
            modifier = Modifier.padding(16.dp)
        )
        Button(onClick = onRetry) {
            Text("Try again")
        }
    }
}
