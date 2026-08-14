/*
 * Copyright (C) 2026 OuterTune VK contributors
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.auth.vk.VkAccount
import com.dd3boh.outertune.auth.vk.VkAuthError
import com.dd3boh.outertune.auth.vk.VkAuthOperation
import com.dd3boh.outertune.auth.vk.VkAuthState
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.viewmodels.VkAuthViewModel
import com.dd3boh.outertune.viewmodels.retryAction

@Composable
fun ColumnScope.VkAccountSection(
    viewModel: VkAuthViewModel = hiltViewModel(),
) {
    val authState by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel) {
        viewModel.restoreSession()
    }

    VkAccountSummary(authState)

    when (val currentState = authState) {
        VkAuthState.NotConfigured -> PreferenceEntry(
            title = { Text(stringResource(R.string.vk_id_configuration_required)) },
            description = stringResource(R.string.vk_id_configuration_required_description),
            icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            onClick = {},
            isEnabled = false,
        )

        VkAuthState.SignedOut -> PreferenceEntry(
            title = { Text(stringResource(R.string.vk_id_connect)) },
            description = stringResource(R.string.vk_id_connect_description),
            icon = { Icon(Icons.Rounded.Person, contentDescription = null) },
            onClick = { viewModel.signIn(lifecycleOwner) },
        )

        is VkAuthState.InProgress -> VkProgressRow(currentState.operation)

        is VkAuthState.SignedIn -> {
            PreferenceEntry(
                title = { Text(stringResource(R.string.vk_id_refresh)) },
                description = stringResource(R.string.vk_id_refresh_description),
                icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                onClick = viewModel::refresh,
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.vk_id_disconnect)) },
                description = stringResource(R.string.vk_id_disconnect_description),
                icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
                onClick = viewModel::signOut,
            )
        }

        is VkAuthState.Failed -> {
            VkErrorRow(currentState.error)
            if (currentState.retryAction() != null) {
                val reconnect = currentState.error == VkAuthError.RefreshTokenExpired ||
                    currentState.error == VkAuthError.AccessRevokedOrSignedOut
                PreferenceEntry(
                    title = {
                        Text(
                            stringResource(
                                if (reconnect) R.string.vk_id_connect_again else R.string.vk_id_retry
                            )
                        )
                    },
                    icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    onClick = { viewModel.retry(lifecycleOwner) },
                )
            }
        }
    }

    VkIdentityOnlyInfoRow()
}

@Composable
private fun VkAccountSummary(state: VkAuthState) {
    val account = state.accountOrNull()
    val status = state.statusPresentation()
    val title = account?.displayName?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.vk_id_title)
    val description = account?.let { stringResource(R.string.vk_id_user_id, it.userId) }
        ?: state.summaryDescription()
    val avatarUrl = account?.avatarUrl?.takeIf(String::isNotBlank)
    val accessibilityLabel = stringResource(R.string.vk_id_account_status_a11y, title, status.label)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Spacer(Modifier.width(12.dp))
        VkStatusChip(status, isError = state is VkAuthState.Failed)
    }
}

@Composable
private fun VkStatusChip(
    status: VkStatusPresentation,
    isError: Boolean,
) {
    val containerColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        status.connected -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        status.connected -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val accessibilityLabel = stringResource(R.string.vk_id_status_a11y, status.label)

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.semantics {
            contentDescription = accessibilityLabel
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            if (status.inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = status.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun VkProgressRow(operation: VkAuthOperation) {
    val text = operation.inProgressLabel()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = text }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun VkErrorRow(error: VkAuthError) {
    val message = error.message()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = message }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun VkIdentityOnlyInfoRow() {
    val message = stringResource(R.string.vk_id_identity_only_description)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = message }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

private fun VkAuthState.accountOrNull(): VkAccount? = when (this) {
    is VkAuthState.SignedIn -> account
    is VkAuthState.InProgress -> previousAccount
    is VkAuthState.Failed -> previousAccount
    VkAuthState.NotConfigured,
    VkAuthState.SignedOut -> null
}

@Composable
private fun VkAuthState.summaryDescription(): String = when (this) {
    VkAuthState.NotConfigured -> stringResource(R.string.vk_id_configuration_required_short)
    VkAuthState.SignedOut -> stringResource(R.string.vk_id_disconnected_description)
    is VkAuthState.InProgress -> operation.inProgressLabel()
    is VkAuthState.SignedIn -> stringResource(R.string.vk_id_connected_description)
    is VkAuthState.Failed -> error.message()
}

@Composable
private fun VkAuthState.statusPresentation(): VkStatusPresentation = when (this) {
    VkAuthState.NotConfigured -> VkStatusPresentation(
        label = stringResource(R.string.vk_id_status_not_configured),
        icon = Icons.Rounded.Settings,
    )
    VkAuthState.SignedOut -> VkStatusPresentation(
        label = stringResource(R.string.vk_id_status_disconnected),
        icon = Icons.Rounded.AccountCircle,
    )
    is VkAuthState.InProgress -> VkStatusPresentation(
        label = stringResource(R.string.vk_id_status_in_progress),
        icon = Icons.Rounded.Refresh,
        inProgress = true,
    )
    is VkAuthState.SignedIn -> VkStatusPresentation(
        label = stringResource(R.string.vk_id_status_connected),
        icon = Icons.Rounded.CheckCircle,
        connected = true,
    )
    is VkAuthState.Failed -> VkStatusPresentation(
        label = stringResource(R.string.vk_id_status_error),
        icon = Icons.Rounded.ErrorOutline,
    )
}

@Composable
private fun VkAuthOperation.inProgressLabel(): String = when (this) {
    VkAuthOperation.AUTHENTICATING -> stringResource(R.string.vk_id_progress_connecting)
    VkAuthOperation.REFRESHING_SESSION -> stringResource(R.string.vk_id_progress_refreshing_session)
    VkAuthOperation.REFRESHING_ACCOUNT -> stringResource(R.string.vk_id_progress_refreshing_account)
    VkAuthOperation.SIGNING_OUT -> stringResource(R.string.vk_id_progress_disconnecting)
}

@Composable
private fun VkAuthError.message(): String = when (this) {
    VkAuthError.ConfigurationMissing -> stringResource(R.string.vk_id_error_configuration)
    VkAuthError.Canceled -> stringResource(R.string.vk_id_error_canceled)
    VkAuthError.NoBrowser -> stringResource(R.string.vk_id_error_no_browser)
    VkAuthError.OAuthStateMismatch -> stringResource(R.string.vk_id_error_state)
    VkAuthError.AuthorizationRejected -> stringResource(R.string.vk_id_error_authorization)
    VkAuthError.RedirectFailure -> stringResource(R.string.vk_id_error_redirect)
    VkAuthError.AccessTokenExpired -> stringResource(R.string.vk_id_error_access_expired)
    VkAuthError.RefreshTokenExpired -> stringResource(R.string.vk_id_error_refresh_expired)
    VkAuthError.AccessRevokedOrSignedOut -> stringResource(R.string.vk_id_error_revoked)
    is VkAuthError.Network -> stringResource(R.string.vk_id_error_network)
    is VkAuthError.ServiceFailure -> stringResource(R.string.vk_id_error_service)
}

private data class VkStatusPresentation(
    val label: String,
    val icon: ImageVector,
    val connected: Boolean = false,
    val inProgress: Boolean = false,
)
