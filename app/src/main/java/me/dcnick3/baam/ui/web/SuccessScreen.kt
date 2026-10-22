package me.dcnick3.baam.ui.web

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.dcnick3.baam.api.baamBaseUrl
import me.dcnick3.baam.ui.web.impl.WebView
import me.dcnick3.baam.ui.web.impl.rememberWebViewNavigator
import me.dcnick3.baam.ui.web.impl.rememberWebViewState

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SuccessfulMark(code: String, summary: List<String> = emptyList()) {
    val state = rememberWebViewState("${baamBaseUrl}MarkedSuccessfully/$code")
    val navigator = rememberWebViewNavigator()

    Column(modifier = Modifier.fillMaxSize()) {
        // the web page only shows one account, so list what happened to each of them
        if (summary.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(16.dp)
            ) {
                for (line in summary) {
                    Text(line, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        WebView(
            modifier = Modifier.weight(1f),
            state = state,
            navigator = navigator,
            onCreated = { webView ->
                webView.settings.javaScriptEnabled = true
            }
        )
    }
}