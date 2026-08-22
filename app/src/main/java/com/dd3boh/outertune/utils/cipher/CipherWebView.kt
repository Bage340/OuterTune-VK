package com.dd3boh.outertune.utils.cipher

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CipherWebView private constructor(
    context: Context,
    private val config: PlayerCipherConfig,
    private val initContinuation: Continuation<CipherWebView>,
) {
    private val webView = WebView(context)
    private var sigContinuation: Continuation<String>? = null
    private var nContinuation: Continuation<String>? = null

    init {
        webView.settings.apply {
            @Suppress("SetJavaScriptEnabled")
            javaScriptEnabled = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            blockNetworkLoads = true
        }
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, "JS error: ${m.message()}")
                return super.onConsoleMessage(m)
            }
        }
    }

    private fun load(cacheDir: File) = webView.loadDataWithBaseURL(
        "file://${cacheDir.absolutePath}/", buildHtml(), "text/html", "utf-8", null
    )

    private fun buildHtml(): String = """<!DOCTYPE html><html><head><script>
function deobfuscateSig(sig){try{var f=window._cipherSigFunc;if(typeof f!=='function'){CipherBridge.onSigError('sig function not available');return;}var r=f(sig);if(r===undefined||r===null){CipherBridge.onSigError('sig function returned null');return;}CipherBridge.onSigResult(String(r));}catch(e){CipherBridge.onSigError(String(e));}}
function transformN(n){try{var f=window._nTransformFunc;if(typeof f!=='function'){CipherBridge.onNError('n function not available');return;}var r=f(n);if(r===undefined||r===null){CipherBridge.onNError('n function returned null');return;}CipherBridge.onNResult(String(r));}catch(e){CipherBridge.onNError(String(e));}}
</script><script src="player.js" onload="CipherBridge.onReady()" onerror="CipherBridge.onLoadError('failed to load player.js')"></script></head><body></body></html>"""

    @JavascriptInterface fun onReady() = initContinuation.resume(this)
    @JavascriptInterface fun onLoadError(error: String) = initContinuation.resumeWithException(CipherException(error))
    @JavascriptInterface fun onSigResult(result: String) { sigContinuation?.resume(result); sigContinuation = null }
    @JavascriptInterface fun onSigError(error: String) { sigContinuation?.resumeWithException(CipherException(error)); sigContinuation = null }
    @JavascriptInterface fun onNResult(result: String) { nContinuation?.resume(result); nContinuation = null }
    @JavascriptInterface fun onNError(error: String) { nContinuation?.resumeWithException(CipherException(error)); nContinuation = null }

    suspend fun deobfuscateSignature(sig: String): String = withContext(Dispatchers.Main) {
        withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { sigContinuation = null }
                sigContinuation = cont
                webView.evaluateJavascript("deobfuscateSig('${escape(sig)}')", null)
            }
        } ?: throw CipherException("sig deobfuscation timed out")
    }

    suspend fun transformNParam(n: String): String = withContext(Dispatchers.Main) {
        withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { nContinuation = null }
                nContinuation = cont
                webView.evaluateJavascript("transformN('${escape(n)}')", null)
            }
        } ?: throw CipherException("n transform timed out")
    }

    fun close() { webView.loadUrl("about:blank"); webView.destroy() }
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    companion object {
        private const val TAG = "CipherWebView"
        private const val JS_INTERFACE = "CipherBridge"
        private const val LOAD_TIMEOUT_MS = 10_000L
        private const val CALL_TIMEOUT_MS = 10_000L
        private const val N_PROBE_URL = "https://x.googlevideo.com/videoplayback?n="

        suspend fun create(context: Context, playerJs: String, config: PlayerCipherConfig): CipherWebView {
            val cacheDir = withContext(Dispatchers.IO) {
                File(context.cacheDir, "cipher").apply { mkdirs() }.also { File(it, "player.js").writeText(buildInjectedPlayerJs(playerJs, config)) }
            }
            return withContext(Dispatchers.Main) {
                val holder = arrayOfNulls<CipherWebView>(1)
                withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont -> CipherWebView(context, config, cont).also { holder[0] = it; it.load(cacheDir) } }
                } ?: run { holder[0]?.close(); throw CipherException("player.js load timed out") }
            }
        }

        private fun buildInjectedPlayerJs(playerJs: String, config: PlayerCipherConfig): String {
            val args = config.sigConstantArgs.joinToString(", ")
            val wrapper = "; window._cipherSigFunc=function(sig){try{return ${config.sigFuncName}($args,sig);}catch(e){return null;}}; window._nTransformFunc=function(n){try{var u=new g.${config.nClass}('$N_PROBE_URL'+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}};"
            val injected = playerJs.replaceFirst("})(_yt_player);", "$wrapper })(_yt_player);")
            return if (injected == playerJs) "$playerJs\n$wrapper" else injected
        }
    }
}

class CipherException(message: String) : Exception(message)
