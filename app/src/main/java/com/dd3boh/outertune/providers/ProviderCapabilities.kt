/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers

enum class ProviderCapability {
    AUTH,
    SEARCH_TRACK,
    LIBRARY_READ,
    LIBRARY_WRITE,
    PLAYLIST_READ,
    PLAYLIST_WRITE,
    PLAYLIST_ORDER_WRITE,
    UPLOAD_LOCAL_AUDIO,
    STREAM,
}

/**
 * Machine-readable reasons are deliberately separate from user-facing text.
 * Consumers can localize a reason without parsing a provider's error message.
 */
enum class CapabilityUnavailableReasonCode {
    NOT_DECLARED,
    NOT_IMPLEMENTED,
    PROVIDER_DISABLED,
    SDK_NOT_CONFIGURED,
    OFFICIAL_API_ACCESS_UNAVAILABLE,
    APP_NOT_ALLOWLISTED,
    AUTH_REQUIRED,
    PERMISSION_NOT_GRANTED,
    REGION_RESTRICTED,
    ACCOUNT_RESTRICTED,
    TEMPORARILY_UNAVAILABLE,
}

sealed interface CapabilityAvailability {
    val isAvailable: Boolean

    data object Available : CapabilityAvailability {
        override val isAvailable: Boolean = true
    }

    data class Unavailable(
        val reasonCode: CapabilityUnavailableReasonCode,
        val detail: String? = null,
    ) : CapabilityAvailability {
        override val isAvailable: Boolean = false
    }
}

/**
 * An exhaustive capability snapshot. Missing declarations are converted to an
 * explicit [CapabilityUnavailableReasonCode.NOT_DECLARED] entry.
 */
class CapabilitySet private constructor(
    entries: Map<ProviderCapability, CapabilityAvailability>,
) {
    private val entries = ProviderCapability.entries.associateWith { capability ->
        entries[capability] ?: CapabilityAvailability.Unavailable(
            reasonCode = CapabilityUnavailableReasonCode.NOT_DECLARED,
        )
    }

    val availableCapabilities: Set<ProviderCapability>
        get() = entries
            .filterValues { it.isAvailable }
            .keys

    fun availability(capability: ProviderCapability): CapabilityAvailability =
        entries.getValue(capability)

    fun supports(capability: ProviderCapability): Boolean =
        availability(capability).isAvailable

    fun asMap(): Map<ProviderCapability, CapabilityAvailability> = entries.toMap()

    fun with(
        capability: ProviderCapability,
        availability: CapabilityAvailability,
    ): CapabilitySet = from(entries + (capability to availability))

    override fun equals(other: Any?): Boolean =
        this === other || other is CapabilitySet && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "CapabilitySet(entries=$entries)"

    companion object {
        fun from(entries: Map<ProviderCapability, CapabilityAvailability>): CapabilitySet =
            CapabilitySet(entries.toMap())

        fun allAvailable(): CapabilitySet = from(
            ProviderCapability.entries.associateWith { CapabilityAvailability.Available }
        )

        fun none(
            reasonCode: CapabilityUnavailableReasonCode,
            detail: String? = null,
        ): CapabilitySet = from(
            ProviderCapability.entries.associateWith {
                CapabilityAvailability.Unavailable(reasonCode, detail)
            }
        )

        fun withAvailable(
            available: Set<ProviderCapability>,
            defaultUnavailableReason: CapabilityUnavailableReasonCode =
                CapabilityUnavailableReasonCode.NOT_IMPLEMENTED,
        ): CapabilitySet = from(
            ProviderCapability.entries.associateWith { capability ->
                if (capability in available) {
                    CapabilityAvailability.Available
                } else {
                    CapabilityAvailability.Unavailable(defaultUnavailableReason)
                }
            }
        )
    }
}
