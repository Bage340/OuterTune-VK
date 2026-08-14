/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers

enum class ProviderErrorCode {
    CAPABILITY_UNAVAILABLE,
    AUTH_REQUIRED,
    INVALID_REQUEST,
    NOT_FOUND,
    CONFLICT,
    NETWORK,
    RATE_LIMITED,
    TOKEN_EXPIRED,
    PERMISSION_REVOKED,
    MALFORMED_RESPONSE,
    CANCELLED,
    UNKNOWN,
}

data class ProviderError(
    val provider: ProviderId,
    val code: ProviderErrorCode,
    val message: String,
    val isRetryable: Boolean = false,
    val unavailableReasonCode: CapabilityUnavailableReasonCode? = null,
)

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>

    data class Failure(val error: ProviderError) : ProviderResult<Nothing>
}

fun <T> ProviderResult<T>.getOrNull(): T? =
    (this as? ProviderResult.Success<T>)?.value

fun CapabilitySet.check(
    provider: ProviderId,
    capability: ProviderCapability,
): ProviderResult<Unit> = when (val availability = availability(capability)) {
    CapabilityAvailability.Available -> ProviderResult.Success(Unit)
    is CapabilityAvailability.Unavailable -> ProviderResult.Failure(
        ProviderError(
            provider = provider,
            code = if (availability.reasonCode == CapabilityUnavailableReasonCode.AUTH_REQUIRED) {
                ProviderErrorCode.AUTH_REQUIRED
            } else {
                ProviderErrorCode.CAPABILITY_UNAVAILABLE
            },
            message = availability.detail
                ?: "${capability.name} is unavailable for ${provider.name}",
            unavailableReasonCode = availability.reasonCode,
        )
    )
}
