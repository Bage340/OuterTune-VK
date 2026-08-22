package com.dd3boh.outertune.utils.cipher

import android.net.Uri
import android.util.Log
import com.dd3boh.outertune.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object SignatureCipherManager {
    private const val TAG = "SignatureCipherManager"
    private val nParamRegex = Regex("[?&]n=([^&]+)")
    private val mutex = Mutex()
    private var webView: CipherWebView? = null
    private var webViewHash: String? = null

    suspend fun deobfuscateStreamUrl(signatureCipher: String, videoId: String): String? = mutex.withLock {
        val params = parseQuery(signatureCipher)
        val obfuscatedSig = params["s"]
        val sigParam = params["sp"] ?: "signature"
        val baseUrl = params["url"]
        if (obfuscatedSig == null || baseUrl == null) return@withLock null

        for (attempt in 0..1) {
            val player = PlayerJsFetcher.getPlayerJs(videoId, attempt > 0) ?: return@withLock null
            val config = PlayerCipherConfigStore.get(player.hash)
            if (config == null) {
                if (attempt == 0 && player.fromCache) { PlayerJsFetcher.invalidate(); continue }
                Log.w(TAG, "[$videoId] no cipher config for player ${player.hash}")
                return@withLock null
            }
            val finalUrl = try {
                val cipher = getOrCreateWebView(player.hash, player.code, config)
                val sig = cipher.deobfuscateSignature(obfuscatedSig)
                val urlWithN = transformNParam(baseUrl, cipher)
                "$urlWithN${if ('?' in urlWithN) '&' else '?'}$sigParam=${Uri.encode(sig)}"
            } catch (e: Exception) { Log.e(TAG, "cipher deobfuscation failed", e); null }
            if (finalUrl != null) return@withLock finalUrl
            PlayerJsFetcher.invalidate(); closeWebView()
        }
        null
    }

    private suspend fun transformNParam(url: String, cipher: CipherWebView): String {
        val match = nParamRegex.find(url) ?: return url
        val original = Uri.decode(match.groupValues[1])
        return try {
            val transformed = cipher.transformNParam(original)
            if (transformed.isEmpty() || transformed == original) url else {
                val lead = url[match.range.first]
                url.substring(0, match.range.first) + lead + "n=" + Uri.encode(transformed) + url.substring(match.range.last + 1)
            }
        } catch (_: Exception) { url }
    }

    private suspend fun getOrCreateWebView(hash: String, js: String, config: PlayerCipherConfig): CipherWebView {
        webView?.let { if (webViewHash == hash) return it else closeWebView() }
        return CipherWebView.create(App.instance, js, config).also { webView = it; webViewHash = hash }
    }

    private suspend fun closeWebView() {
        webView?.let { withContext(Dispatchers.Main) { it.close() } }
        webView = null; webViewHash = null
    }

    private fun parseQuery(query: String): Map<String, String> = buildMap {
        for (pair in query.split("&")) {
            val i = pair.indexOf('=')
            if (i > 0) put(Uri.decode(pair.substring(0, i)), Uri.decode(pair.substring(i + 1)))
        }
    }
}
