package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.auth.vk.VkAuthError
import com.dd3boh.outertune.auth.vk.VkAuthOperation
import com.dd3boh.outertune.auth.vk.VkAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VkAuthViewModelTest {
    @Test
    fun expiredRefreshTokenRequiresSignIn() {
        val state = VkAuthState.Failed(VkAuthError.RefreshTokenExpired, canRetry = false)

        assertEquals(VkAuthRetryAction.SIGN_IN, state.retryAction())
    }

    @Test
    fun expiredAccessTokenRefreshesSession() {
        val state = VkAuthState.Failed(VkAuthError.AccessTokenExpired, canRetry = true)

        assertEquals(VkAuthRetryAction.REFRESH_SESSION, state.retryAction())
    }

    @Test
    fun networkFailureRetriesItsExactOperation() {
        val state = VkAuthState.Failed(
            error = VkAuthError.Network(VkAuthOperation.REFRESHING_ACCOUNT),
            canRetry = true,
        )

        assertEquals(VkAuthRetryAction.REFRESH_ACCOUNT, state.retryAction())
    }

    @Test
    fun nonRetryableServiceFailureDoesNotOfferRetry() {
        val state = VkAuthState.Failed(
            error = VkAuthError.ServiceFailure(VkAuthOperation.SIGNING_OUT),
            canRetry = false,
        )

        assertNull(state.retryAction())
    }

    @Test
    fun nonFailureStateHasNoRetryAction() {
        assertNull(VkAuthState.SignedOut.retryAction())
    }
}
