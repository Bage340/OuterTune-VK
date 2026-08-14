/*
 * Copyright (C) 2026 OuterTune VK contributors
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.auth.vk

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-facing VK ID authentication boundary.
 *
 * It deliberately exposes account metadata but never access, refresh, or ID token strings. The
 * official VK ID SDK remains the single owner of token persistence.
 */
interface VkAuthManager {
    val configured: Boolean
    val state: StateFlow<VkAuthState>

    /** Re-reads the locally cached SDK session without making a network request. */
    fun restoreSession(): VkAuthState

    /** Starts the official VK ID browser/app authorization flow. */
    fun signIn(
        lifecycleOwner: LifecycleOwner,
        requestedScopes: Set<VkAuthScope> = emptySet(),
    )

    /** Refreshes the SDK-managed access/refresh token pair. */
    suspend fun refreshSession(): VkAuthState

    /** Fetches current account data. VK ID may refresh an invalid access token internally. */
    suspend fun refreshAccount(): VkAuthState

    /** Revokes the current session through VK ID and clears the SDK-managed local token state. */
    suspend fun signOut(): VkAuthState
}

sealed interface VkAuthState {
    data object NotConfigured : VkAuthState
    data object SignedOut : VkAuthState

    data class InProgress(
        val operation: VkAuthOperation,
        val previousAccount: VkAccount? = null,
    ) : VkAuthState

    data class SignedIn(val account: VkAccount) : VkAuthState

    data class Failed(
        val error: VkAuthError,
        val previousAccount: VkAccount? = null,
        val canRetry: Boolean,
    ) : VkAuthState
}

enum class VkAuthOperation {
    AUTHENTICATING,
    REFRESHING_SESSION,
    REFRESHING_ACCOUNT,
    SIGNING_OUT,
}

/** Only scopes documented by VK ID as of 2026-08-14. There is intentionally no audio scope. */
enum class VkAuthScope(internal val wireValue: String) {
    PERSONAL_INFO("vkid.personal_info"),
    EMAIL("email"),
    PHONE("phone"),
}

/** Safe account projection. Token values are intentionally absent. */
data class VkAccount(
    val userId: Long,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val phone: String?,
    val avatarUrl: String?,
    val grantedScopes: Set<String>,
    val accessTokenExpiresAtMillis: Long?,
) {
    val displayName: String
        get() = listOf(firstName, lastName).filter(String::isNotBlank).joinToString(" ")
}

/** Sanitized, UI-safe failure categories. Raw tokens and server responses are never retained. */
sealed interface VkAuthError {
    data object ConfigurationMissing : VkAuthError
    data object Canceled : VkAuthError
    data object NoBrowser : VkAuthError
    data object OAuthStateMismatch : VkAuthError
    data object AuthorizationRejected : VkAuthError
    data object RedirectFailure : VkAuthError
    data object AccessTokenExpired : VkAuthError
    data object RefreshTokenExpired : VkAuthError
    data object AccessRevokedOrSignedOut : VkAuthError
    data class Network(val operation: VkAuthOperation) : VkAuthError
    data class ServiceFailure(val operation: VkAuthOperation) : VkAuthError
}
