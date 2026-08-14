/*
 * Copyright (C) 2026 OuterTune VK contributors
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.auth.vk

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.dd3boh.outertune.BuildConfig
import com.vk.id.AccessToken
import com.vk.id.VKID
import com.vk.id.VKIDAuthFail
import com.vk.id.VKIDUser
import com.vk.id.auth.VKIDAuthCallback
import com.vk.id.auth.VKIDAuthParams
import com.vk.id.logout.VKIDLogoutCallback
import com.vk.id.logout.VKIDLogoutFail
import com.vk.id.refresh.VKIDRefreshTokenCallback
import com.vk.id.refresh.VKIDRefreshTokenFail
import com.vk.id.refreshuser.VKIDGetUserCallback
import com.vk.id.refreshuser.VKIDGetUserFail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VK ID 2.7.2 implementation backed only by the official public SDK surface.
 *
 * The SDK owns encrypted token persistence. This class never copies token values to DataStore,
 * logs, exceptions, or account models.
 */
class OfficialVkAuthManager(context: Context) : VkAuthManager {
    override val configured: Boolean = BuildConfig.VK_ID_CONFIGURED

    private val appContext = context.applicationContext
    private val operationInFlight = AtomicBoolean(false)
    private val sdk: VKID? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (configured) VkIdSdkHolder.get(appContext) else null
    }

    private val mutableState = MutableStateFlow<VkAuthState>(VkAuthState.NotConfigured)
    override val state: StateFlow<VkAuthState> = mutableState.asStateFlow()

    init {
        mutableState.value = if (configured) snapshotFromSdk() else VkAuthState.NotConfigured
    }

    override fun restoreSession(): VkAuthState {
        if (!configured) return publish(VkAuthState.NotConfigured)
        if (operationInFlight.get()) return state.value
        return publish(snapshotFromSdk())
    }

    override fun signIn(
        lifecycleOwner: LifecycleOwner,
        requestedScopes: Set<VkAuthScope>,
    ) {
        val vkid = configuredSdk() ?: return
        if (!operationInFlight.compareAndSet(false, true)) return

        val previousAccount = currentAccount()
        publish(VkAuthState.InProgress(VkAuthOperation.AUTHENTICATING, previousAccount))

        try {
            vkid.authorize(
                lifecycleOwner = lifecycleOwner,
                callback = object : VKIDAuthCallback {
                    override fun onAuth(accessToken: AccessToken) {
                        operationInFlight.set(false)
                        publish(VkAuthState.SignedIn(accessToken.toAccount()))
                    }

                    override fun onFail(fail: VKIDAuthFail) {
                        operationInFlight.set(false)
                        publish(mapAuthFailure(fail, previousAccount))
                    }
                },
                params = VKIDAuthParams {
                    scopes = requestedScopes.mapTo(mutableSetOf()) { it.wireValue }
                },
            )
        } catch (throwable: Exception) {
            operationInFlight.set(false)
            publish(mapUnexpectedFailure(VkAuthOperation.AUTHENTICATING, throwable, previousAccount))
        }
    }

    override suspend fun refreshSession(): VkAuthState {
        val vkid = configuredSdk() ?: return state.value
        if (!operationInFlight.compareAndSet(false, true)) return state.value

        val previousAccount = currentAccount()
        publish(VkAuthState.InProgress(VkAuthOperation.REFRESHING_SESSION, previousAccount))
        return try {
            val newState = when (val result = vkid.refreshTokenAwait()) {
                is RefreshResult.Success -> VkAuthState.SignedIn(result.token.toAccount())
                is RefreshResult.Failure -> mapRefreshFailure(result.failure, previousAccount)
            }
            publish(newState)
        } catch (cancellation: CancellationException) {
            publish(snapshotFromSdk())
            throw cancellation
        } catch (throwable: Exception) {
            publish(mapUnexpectedFailure(VkAuthOperation.REFRESHING_SESSION, throwable, previousAccount))
        } finally {
            operationInFlight.set(false)
        }
    }

    override suspend fun refreshAccount(): VkAuthState {
        val vkid = configuredSdk() ?: return state.value
        if (!operationInFlight.compareAndSet(false, true)) return state.value

        val previousAccount = currentAccount()
        publish(VkAuthState.InProgress(VkAuthOperation.REFRESHING_ACCOUNT, previousAccount))
        return try {
            val newState = when (val result = vkid.getUserDataAwait()) {
                is UserResult.Success -> {
                    val account = vkid.accessToken?.toAccount()
                        ?: previousAccount?.withUpdatedUser(result.user)
                    if (account != null) {
                        VkAuthState.SignedIn(account)
                    } else {
                        VkAuthState.Failed(
                            error = VkAuthError.ServiceFailure(VkAuthOperation.REFRESHING_ACCOUNT),
                            canRetry = true,
                        )
                    }
                }
                is UserResult.Failure -> mapUserFailure(result.failure, previousAccount)
            }
            publish(newState)
        } catch (cancellation: CancellationException) {
            publish(snapshotFromSdk())
            throw cancellation
        } catch (throwable: Exception) {
            publish(mapUnexpectedFailure(VkAuthOperation.REFRESHING_ACCOUNT, throwable, previousAccount))
        } finally {
            operationInFlight.set(false)
        }
    }

    override suspend fun signOut(): VkAuthState {
        val vkid = configuredSdk() ?: return state.value
        if (!operationInFlight.compareAndSet(false, true)) return state.value
        if (vkid.accessToken == null) {
            operationInFlight.set(false)
            return publish(VkAuthState.SignedOut)
        }

        val previousAccount = currentAccount()
        publish(VkAuthState.InProgress(VkAuthOperation.SIGNING_OUT, previousAccount))
        return try {
            val newState = when (val result = vkid.logoutAwait()) {
                LogoutResult.Success -> VkAuthState.SignedOut
                is LogoutResult.Failure -> mapLogoutFailure(result.failure, previousAccount)
            }
            publish(newState)
        } catch (cancellation: CancellationException) {
            publish(snapshotFromSdk())
            throw cancellation
        } catch (throwable: Exception) {
            publish(mapUnexpectedFailure(VkAuthOperation.SIGNING_OUT, throwable, previousAccount))
        } finally {
            operationInFlight.set(false)
        }
    }

    private fun configuredSdk(): VKID? {
        if (!configured) publish(VkAuthState.NotConfigured)
        return sdk
    }

    private fun snapshotFromSdk(): VkAuthState {
        val token = sdk?.accessToken ?: return VkAuthState.SignedOut
        val account = token.toAccount()
        return if (token.isExpired()) {
            VkAuthState.Failed(
                error = VkAuthError.AccessTokenExpired,
                previousAccount = account,
                canRetry = true,
            )
        } else {
            VkAuthState.SignedIn(account)
        }
    }

    private fun currentAccount(): VkAccount? = sdk?.accessToken?.toAccount()

    private fun publish(newState: VkAuthState): VkAuthState {
        mutableState.value = newState
        return newState
    }

    private fun mapAuthFailure(
        failure: VKIDAuthFail,
        previousAccount: VkAccount?,
    ): VkAuthState.Failed = when (failure) {
        is VKIDAuthFail.Canceled -> VkAuthState.Failed(
            error = VkAuthError.Canceled,
            previousAccount = previousAccount,
            canRetry = true,
        )
        is VKIDAuthFail.FailedApiCall -> apiFailure(
            operation = VkAuthOperation.AUTHENTICATING,
            throwable = failure.throwable,
            previousAccount = previousAccount,
        )
        is VKIDAuthFail.FailedOAuth -> VkAuthState.Failed(
            error = VkAuthError.AuthorizationRejected,
            previousAccount = previousAccount,
            canRetry = true,
        )
        is VKIDAuthFail.FailedOAuthState -> VkAuthState.Failed(
            error = VkAuthError.OAuthStateMismatch,
            previousAccount = previousAccount,
            canRetry = false,
        )
        is VKIDAuthFail.FailedRedirectActivity -> VkAuthState.Failed(
            error = VkAuthError.RedirectFailure,
            previousAccount = previousAccount,
            canRetry = false,
        )
        is VKIDAuthFail.NoBrowserAvailable -> VkAuthState.Failed(
            error = VkAuthError.NoBrowser,
            previousAccount = previousAccount,
            canRetry = false,
        )
    }

    private fun mapRefreshFailure(
        failure: VKIDRefreshTokenFail,
        previousAccount: VkAccount?,
    ): VkAuthState.Failed = when (failure) {
        is VKIDRefreshTokenFail.FailedApiCall -> apiFailure(
            operation = VkAuthOperation.REFRESHING_SESSION,
            throwable = failure.throwable,
            previousAccount = previousAccount,
        )
        is VKIDRefreshTokenFail.FailedOAuthState -> VkAuthState.Failed(
            error = VkAuthError.OAuthStateMismatch,
            previousAccount = previousAccount,
            canRetry = false,
        )
        is VKIDRefreshTokenFail.NotAuthenticated -> VkAuthState.Failed(
            error = VkAuthError.AccessRevokedOrSignedOut,
            previousAccount = previousAccount,
            canRetry = false,
        )
        is VKIDRefreshTokenFail.RefreshTokenExpired -> VkAuthState.Failed(
            error = VkAuthError.RefreshTokenExpired,
            previousAccount = previousAccount,
            canRetry = false,
        )
    }

    private fun mapUserFailure(
        failure: VKIDGetUserFail,
        previousAccount: VkAccount?,
    ): VkAuthState.Failed = when (failure) {
        is VKIDGetUserFail.FailedApiCall -> apiFailure(
            operation = VkAuthOperation.REFRESHING_ACCOUNT,
            throwable = failure.throwable,
            previousAccount = previousAccount,
        )
        is VKIDGetUserFail.IdTokenTokenExpired -> VkAuthState.Failed(
            error = VkAuthError.AccessTokenExpired,
            previousAccount = previousAccount,
            canRetry = true,
        )
        is VKIDGetUserFail.NotAuthenticated -> VkAuthState.Failed(
            error = VkAuthError.AccessRevokedOrSignedOut,
            previousAccount = previousAccount,
            canRetry = false,
        )
    }

    private fun mapLogoutFailure(
        failure: VKIDLogoutFail,
        previousAccount: VkAccount?,
    ): VkAuthState = when (failure) {
        is VKIDLogoutFail.AccessTokenTokenExpired,
        is VKIDLogoutFail.NotAuthenticated -> VkAuthState.SignedOut
        is VKIDLogoutFail.FailedApiCall -> apiFailure(
            operation = VkAuthOperation.SIGNING_OUT,
            throwable = failure.throwable,
            previousAccount = previousAccount,
        )
    }

    private fun apiFailure(
        operation: VkAuthOperation,
        throwable: Throwable,
        previousAccount: VkAccount?,
    ) = VkAuthState.Failed(
        error = if (throwable.hasNetworkCause()) {
            VkAuthError.Network(operation)
        } else {
            VkAuthError.ServiceFailure(operation)
        },
        previousAccount = previousAccount,
        canRetry = true,
    )

    private fun mapUnexpectedFailure(
        operation: VkAuthOperation,
        throwable: Throwable,
        previousAccount: VkAccount?,
    ) = apiFailure(operation, throwable, previousAccount)

    private suspend fun VKID.refreshTokenAwait(): RefreshResult {
        val result = CompletableDeferred<RefreshResult>()
        refreshToken(
            callback = object : VKIDRefreshTokenCallback {
                override fun onSuccess(token: AccessToken) {
                    result.complete(RefreshResult.Success(token))
                }

                override fun onFail(fail: VKIDRefreshTokenFail) {
                    result.complete(RefreshResult.Failure(fail))
                }
            },
        )
        return result.await()
    }

    private suspend fun VKID.getUserDataAwait(): UserResult {
        val result = CompletableDeferred<UserResult>()
        getUserData(
            callback = object : VKIDGetUserCallback {
                override fun onSuccess(user: VKIDUser) {
                    result.complete(UserResult.Success(user))
                }

                override fun onFail(fail: VKIDGetUserFail) {
                    result.complete(UserResult.Failure(fail))
                }
            },
        )
        return result.await()
    }

    private suspend fun VKID.logoutAwait(): LogoutResult {
        val result = CompletableDeferred<LogoutResult>()
        logout(
            callback = object : VKIDLogoutCallback {
                override fun onSuccess() {
                    result.complete(LogoutResult.Success)
                }

                override fun onFail(fail: VKIDLogoutFail) {
                    result.complete(LogoutResult.Failure(fail))
                }
            },
        )
        return result.await()
    }

    private sealed interface RefreshResult {
        data class Success(val token: AccessToken) : RefreshResult
        data class Failure(val failure: VKIDRefreshTokenFail) : RefreshResult
    }

    private sealed interface UserResult {
        data class Success(val user: VKIDUser) : UserResult
        data class Failure(val failure: VKIDGetUserFail) : UserResult
    }

    private sealed interface LogoutResult {
        data object Success : LogoutResult
        data class Failure(val failure: VKIDLogoutFail) : LogoutResult
    }
}

private object VkIdSdkHolder {
    private val lock = Any()

    fun get(context: Context): VKID = synchronized(lock) {
        try {
            VKID.instance
        } catch (_: IllegalStateException) {
            VKID.init(context = context.applicationContext, groupSubscriptionLimit = null)
            VKID.instance
        }
    }
}

private fun AccessToken.isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
    expireTime != -1L && expireTime <= nowMillis

private fun AccessToken.toAccount(): VkAccount = VkAccount(
    userId = userID,
    firstName = userData.firstName,
    lastName = userData.lastName,
    email = userData.email,
    phone = userData.phone,
    avatarUrl = userData.photo200 ?: userData.photo100 ?: userData.photo50,
    grantedScopes = scopes.orEmpty().toSet(),
    accessTokenExpiresAtMillis = expireTime.takeUnless { it == -1L },
)

private fun VkAccount.withUpdatedUser(user: VKIDUser): VkAccount = copy(
    firstName = user.firstName,
    lastName = user.lastName,
    email = user.email,
    phone = user.phone,
    avatarUrl = user.photo200 ?: user.photo100 ?: user.photo50,
)

private fun Throwable.hasNetworkCause(): Boolean {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        val candidate = current ?: return false
        if (candidate is IOException) return true
        current = candidate.cause
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 16
