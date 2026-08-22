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

data class PlayerCipherConfig(
    val sigFuncName: String,
    val sigConstantArgs: List<Int>,
    val nClass: String,
)

object PlayerCipherConfigStore {
    private const val TAG = "PlayerCipherConfig"
    private const val ASSET_NAME = "player_configs.json"

    private val configs: Map<String, PlayerCipherConfig> by lazy { load() }

    fun get(playerHash: String?): PlayerCipherConfig? = playerHash?.let { configs[it] }
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
                for (i in 0 until aliases.length()) result[aliases.getString(i)] = config
            }
        }
        Log.d(TAG, "Loaded ${result.size} player cipher configs")
        result
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load $ASSET_NAME", e)
        emptyMap()
    }

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
