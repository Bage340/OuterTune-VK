package com.dd3boh.outertune.providers.syncengine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SyncMutationPayload(
    val title: String? = null,
    val description: String? = null,
    val trackId: String? = null,
    val position: Int? = null,
    val orderedTrackIds: List<String> = emptyList(),
)

object SyncMutationPayloadCodec {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun encode(payload: SyncMutationPayload): String = buildJsonObject {
        put("title", payload.title?.let { JsonPrimitive(it) } ?: JsonNull)
        put("description", payload.description?.let { JsonPrimitive(it) } ?: JsonNull)
        put("trackId", payload.trackId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("position", payload.position?.let { JsonPrimitive(it) } ?: JsonNull)
        put(
            "orderedTrackIds",
            JsonArray(payload.orderedTrackIds.map { JsonPrimitive(it) }),
        )
    }.toString()

    fun decode(payload: String?): SyncMutationPayload = if (payload == null) {
        SyncMutationPayload()
    } else {
        val value = json.parseToJsonElement(payload).jsonObject
        SyncMutationPayload(
            title = value["title"]?.jsonPrimitive?.contentOrNull,
            description = value["description"]?.jsonPrimitive?.contentOrNull,
            trackId = value["trackId"]?.jsonPrimitive?.contentOrNull,
            position = value["position"]?.jsonPrimitive?.intOrNull,
            orderedTrackIds = value["orderedTrackIds"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                .orEmpty(),
        )
    }
}
