/*
 * Copyright (C) 2026 OuterTune VK contributors
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.auth.vk.VkAuthError
import com.dd3boh.outertune.auth.vk.VkAuthManager
import com.dd3boh.outertune.auth.vk.VkAuthOperation
import com.dd3boh.outertune.auth.vk.VkAuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VkAuthViewModel @Inject constructor(
    private val authManager: VkAuthManager,
) : ViewModel() {
    val state: StateFlow<VkAuthState> = authManager.state

    fun restoreSession() {
        if (state.value !is VkAuthState.InProgress) authManager.restoreSession()
    }

    fun signIn(lifecycleOwner: LifecycleOwner) {
        if (state.value is VkAuthState.InProgress) return
        // Empty scopes use VK ID's default personal-info scope. Do not request email/phone needlessly.
        authManager.signIn(lifecycleOwner = lifecycleOwner)
    }

    fun refresh() {
        if (state.value is VkAuthState.InProgress) return
        viewModelScope.launch {
            if (authManager.refreshSession() is VkAuthState.SignedIn) {
                authManager.refreshAccount()
            }
        }
    }

    fun refreshAccount() {
        if (state.value is VkAuthState.InProgress) return
        viewModelScope.launch { authManager.refreshAccount() }
    }

    fun signOut() {
        if (state.value is VkAuthState.InProgress) return
        viewModelScope.launch { authManager.signOut() }
    }

    fun retry(lifecycleOwner: LifecycleOwner) {
        when (state.value.retryAction()) {
            VkAuthRetryAction.SIGN_IN -> signIn(lifecycleOwner)
            VkAuthRetryAction.REFRESH_SESSION -> refresh()
            VkAuthRetryAction.REFRESH_ACCOUNT -> refreshAccount()
            VkAuthRetryAction.SIGN_OUT -> signOut()
            null -> Unit
        }
    }
}

internal enum class VkAuthRetryAction {
    SIGN_IN,
    REFRESH_SESSION,
    REFRESH_ACCOUNT,
    SIGN_OUT,
}

internal fun VkAuthState.retryAction(): VkAuthRetryAction? {
    if (this !is VkAuthState.Failed) return null
    return when (val authError = error) {
        VkAuthError.Canceled,
        VkAuthError.AuthorizationRejected,
        VkAuthError.RefreshTokenExpired,
        VkAuthError.AccessRevokedOrSignedOut -> VkAuthRetryAction.SIGN_IN

        VkAuthError.AccessTokenExpired -> VkAuthRetryAction.REFRESH_SESSION

        is VkAuthError.Network -> authError.operation.toRetryAction().takeIf { canRetry }
        is VkAuthError.ServiceFailure -> authError.operation.toRetryAction().takeIf { canRetry }

        VkAuthError.ConfigurationMissing,
        VkAuthError.NoBrowser,
        VkAuthError.OAuthStateMismatch,
        VkAuthError.RedirectFailure -> null
    }
}

private fun VkAuthOperation.toRetryAction(): VkAuthRetryAction = when (this) {
    VkAuthOperation.AUTHENTICATING -> VkAuthRetryAction.SIGN_IN
    VkAuthOperation.REFRESHING_SESSION -> VkAuthRetryAction.REFRESH_SESSION
    VkAuthOperation.REFRESHING_ACCOUNT -> VkAuthRetryAction.REFRESH_ACCOUNT
    VkAuthOperation.SIGNING_OUT -> VkAuthRetryAction.SIGN_OUT
}
