/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils.cipher

import android.util.Log
import com.dd3boh.outertune.App
import org.json.JSONObject

/**
 * Signature deobfuscation config for one player.js, looked up by its hash and used by [CipherWebView].
 *
 * @property sigFuncName name of the deobfuscation function
 * @property sigConstantArgs constants placed before the signature, so the call is
 *   `sigFuncName(sigConstantArgs..., sig)`
 * @property nClass the player's URL builder class used to apply the n-transform to the `n`
 *   throttling parameter
 */
data class PlayerCipherConfig(
    val sigFuncName: String,
    val sigConstantArgs: List<Int>,
    val nClass: String,
)

/**
 * Provides player.js cipher configs keyed by the 8-hex player hash (aliases included).
 *
 * The data is the bundled `player_configs.json` from ZemerTeam/zemer-cipher
 * (https://github.com/ZemerTeam/zemer-cipher, GPL-3.0), whose upstream validates each entry
 * against the live CDN before shipping it. It is bundled only, with no runtime fetch: to follow a
 * new YouTube player rotation, refresh the bundled file from upstream and release a new build.
 */
object PlayerCipherConfigStore {

    private const val TAG = "PlayerCipherConfig"
    private const val ASSET_NAME = "player_configs.json"

    private val configs: Map<String, PlayerCipherConfig> by lazy { load() }

    fun get(playerHash: String?): PlayerCipherConfig? = playerHash?.let { configs[it] }

    /** All known player hashes, aliases included. For diagnostics only. */
    fun knownHashes(): Set<String> = configs.keys

    private fun load(): Map<String, PlayerCipherConfig> = try {
        val text = App.instance.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val players = JSONObject(text).getJSONObject("players")
        val result = mutableMapOf<String, PlayerCipherConfig>()
        players.keys().forEach { hash ->
            val entry = players.getJSONObject(hash)
            val config = parseEntry(entry) ?: return@forEach
            result[hash] = config
            entry.optJSONArray("aliases")?.let { aliases ->
                for (i in 0 until aliases.length()) {
                    result[aliases.getString(i)] = config
                }
            }
        }
        Log.d(TAG, "Loaded ${result.size} player cipher configs")
        result
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load $ASSET_NAME", e)
        emptyMap()
    }

    // sig is a `name(int,int,INPUT)` call; returns null on any malformed field so one bad entry can't break the map.
    private fun parseEntry(entry: JSONObject): PlayerCipherConfig? {
        val sig = entry.optString("sig")
        val nClass = entry.optString("nClass")
        if (sig.isEmpty() || nClass.isEmpty()) return null

        val open = sig.indexOf('(')
        if (open <= 0 || !sig.endsWith(")")) return null
        val funcName = sig.substring(0, open)
        val args = sig.substring(open + 1, sig.length - 1).split(",").map { it.trim() }
        if (args.lastOrNull() != "INPUT") return null
        val constants = args.dropLast(1).map { it.toIntOrNull() ?: return null }
        if (constants.isEmpty()) return null

        return PlayerCipherConfig(funcName, constants, nClass)
    }
}
