package com.dd3boh.outertune.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AccountChannelHandleKey
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.constants.VisitorDataKey
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONTokener

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, DelicateCoroutinesApi::class)
@Composable
fun LoginScreen(
    navController: NavController,
) {
    var visitorData by rememberPreference(VisitorDataKey, "")
    var dataSyncId by rememberPreference(DataSyncIdKey, "")
    var innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    var accountName by rememberPreference(AccountNameKey, "")
    var accountEmail by rememberPreference(AccountEmailKey, "")
    var accountChannelHandle by rememberPreference(AccountChannelHandleKey, "")

    var webView: WebView? = null

    AndroidView(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val navigation = request ?: return true
                        if (!navigation.isForMainFrame) return false
                        return !navigation.url.isTrustedLoginUri()
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        val uri = url?.let(Uri::parse)
                        if (uri?.scheme != "https" || uri.host != MUSIC_YOUTUBE_HOST) return

                        evaluateJavascript("(window.yt && window.yt.config_) ? window.yt.config_.VISITOR_DATA : null") { result ->
                            decodeJavaScriptString(result)?.let { visitorData = it }
                        }
                        evaluateJavascript("(window.yt && window.yt.config_) ? window.yt.config_.DATASYNC_ID : null") { result ->
                            decodeJavaScriptString(result)?.let { dataSyncId = it.substringBefore("||") }
                        }

                        innerTubeCookie = CookieManager.getInstance().getCookie(url).orEmpty()
                        GlobalScope.launch {
                            YouTube.accountInfo().onSuccess {
                                accountName = it.name
                                accountEmail = it.email.orEmpty()
                                accountChannelHandle = it.channelHandle.orEmpty()
                            }.onFailure {
                                reportException(it)
                            }
                        }
                    }
                }
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportZoom(true)
                    builtInZoomControls = true
                }
                webView = this
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
            }
        }
    )

    TopAppBar(
        title = { Text(stringResource(R.string.login)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
    )

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}

private fun Uri.isTrustedLoginUri(): Boolean {
    if (scheme != "https") return false
    val normalizedHost = host?.lowercase() ?: return false
    return normalizedHost in TRUSTED_LOGIN_HOSTS
}

private fun decodeJavaScriptString(value: String?): String? {
    if (value.isNullOrBlank() || value == "null") return null
    return runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
}

private const val MUSIC_YOUTUBE_HOST = "music.youtube.com"
private val TRUSTED_LOGIN_HOSTS = setOf(
    "accounts.google.com",
    "myaccount.google.com",
    "accounts.youtube.com",
    "consent.youtube.com",
    "www.youtube.com",
    MUSIC_YOUTUBE_HOST,
)
