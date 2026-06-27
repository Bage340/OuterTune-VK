/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

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
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs YouTube's signature deobfuscation function inside the full player.js.
 *
 * Recent players obfuscate the signature with a multi-purpose function that depends on the
 * player's global string array and other internal helpers, so it cannot be extracted and run in
 * isolation. We load the whole player.js into a WebView and inject a wrapper, inside the player's
 * IIFE so the function is in scope (the call below is the current player's; the name and constants
 * come from [PlayerCipherConfig]):
 *
 *     window._cipherSigFunc = function(sig) { return fJ(24, 1210, sig); };
 *
 * Modeled on [com.dd3boh.outertune.utils.potoken.PoTokenWebView] for the WebView/coroutine bridge.
 */
class CipherWebView private constructor(
    context: Context,
    private val playerJs: String,
    private val config: PlayerCipherConfig,
    private val initContinuation: Continuation<CipherWebView>,
) {
    private val webView = WebView(context)
    private var sigContinuation: Continuation<String>? = null
    private var nContinuation: Continuation<String>? = null

    init {
        val settings = webView.settings
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        // Deprecated in API 30, but required to run the local file:// player.js with no replacement.
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        settings.blockNetworkLoads = true // player.js is loaded from a local file

        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(TAG, "JS error: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    private fun loadPlayerJs() {
        val argsStr = config.sigConstantArgs.joinToString(", ")
        val sigWrapper =
            "window._cipherSigFunc = function(sig) { try { return ${config.sigFuncName}($argsStr, sig); } catch(e) { return null; } };"
        // n-transform: build a googlevideo URL carrying the n value and read it back through the
        // player's URL class, which applies the n-transform on read.
        val nWrapper =
            "window._nTransformFunc = function(n) { try { var u = new g.${config.nClass}('https://x.googlevideo.com/videoplayback?n=' + n, true); var t = u.get('n'); return (t && t !== n) ? t : n; } catch(e) { return n; } };"
        val wrapper = "; $sigWrapper $nWrapper"

        val injected = playerJs.replace("})(_yt_player);", "$wrapper })(_yt_player);")
        val modifiedJs = if (injected == playerJs) {
            Log.w(TAG, "IIFE injection point not found, appending wrapper (sig may be out of scope)")
            playerJs + "\n" + wrapper
        } else {
            injected
        }

        val cacheDir = File(webView.context.cacheDir, "cipher").apply { mkdirs() }
        File(cacheDir, "player.js").writeText(modifiedJs)

        webView.loadDataWithBaseURL(
            "file://${cacheDir.absolutePath}/",
            buildHtml(),
            "text/html",
            "utf-8",
            null,
        )
    }

    private fun buildHtml(): String = """<!DOCTYPE html>
<html><head><script>
function deobfuscateSig(sig) {
    try {
        var f = window._cipherSigFunc;
        if (typeof f !== 'function') { $JS_INTERFACE.onSigError('sig function not available'); return; }
        var r = f(sig);
        if (r === undefined || r === null) { $JS_INTERFACE.onSigError('sig function returned null'); return; }
        $JS_INTERFACE.onSigResult(String(r));
    } catch (e) { $JS_INTERFACE.onSigError(e + '\n' + (e.stack || '')); }
}
function transformN(n) {
    try {
        var f = window._nTransformFunc;
        if (typeof f !== 'function') { $JS_INTERFACE.onNError('n function not available'); return; }
        var r = f(n);
        if (r === undefined || r === null) { $JS_INTERFACE.onNError('n function returned null'); return; }
        $JS_INTERFACE.onNResult(String(r));
    } catch (e) { $JS_INTERFACE.onNError(e + '\n' + (e.stack || '')); }
}
</script>
<script src="player.js"
    onload="$JS_INTERFACE.onReady()"
    onerror="$JS_INTERFACE.onLoadError('failed to load player.js')">
</script></head><body></body></html>"""

    @JavascriptInterface
    fun onReady() {
        Log.d(TAG, "player.js loaded, cipher ready (sigFunc=${config.sigFuncName})")
        initContinuation.resume(this)
    }

    @JavascriptInterface
    fun onLoadError(error: String) {
        Log.e(TAG, "player.js load failed: $error")
        initContinuation.resumeWithException(CipherException("player.js load failed: $error"))
    }

    @JavascriptInterface
    fun onSigResult(result: String) {
        sigContinuation?.resume(result)
        sigContinuation = null
    }

    @JavascriptInterface
    fun onSigError(error: String) {
        Log.e(TAG, "sig deobfuscation error: $error")
        sigContinuation?.resumeWithException(CipherException("sig deobfuscation failed: $error"))
        sigContinuation = null
    }

    @JavascriptInterface
    fun onNResult(result: String) {
        nContinuation?.resume(result)
        nContinuation = null
    }

    @JavascriptInterface
    fun onNError(error: String) {
        Log.e(TAG, "n transform error: $error")
        nContinuation?.resumeWithException(CipherException("n transform failed: $error"))
        nContinuation = null
    }

    /**
     * Deobfuscates [obfuscatedSig] by running the player's signature function.
     *
     * @return the deobfuscated signature
     */
    suspend fun deobfuscateSignature(obfuscatedSig: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { sigContinuation = null }
            sigContinuation = cont
            webView.evaluateJavascript("deobfuscateSig('${escapeJsString(obfuscatedSig)}')", null)
        }
    }

    /**
     * Applies the player's n-transform to the throttling parameter [n].
     *
     * @return the transformed value, or [n] unchanged if the transform does not apply
     */
    suspend fun transformNParam(n: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { nContinuation = null }
            nContinuation = cont
            webView.evaluateJavascript("transformN('${escapeJsString(n)}')", null)
        }
    }

    fun close() {
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    private fun escapeJsString(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    companion object {
        private const val TAG = "CipherWebView"
        private const val JS_INTERFACE = "CipherBridge"

        /**
         * Creates a [CipherWebView] and suspends until the player.js has loaded and its cipher
         * functions are ready to call.
         *
         * @return the ready instance
         */
        suspend fun create(
            context: Context,
            playerJs: String,
            config: PlayerCipherConfig,
        ): CipherWebView = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                CipherWebView(context, playerJs, config, cont).loadPlayerJs()
            }
        }
    }
}

class CipherException(message: String) : Exception(message)
