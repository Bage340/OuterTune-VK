/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.AccountChannelHandleKey
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.AccountImageFetchedKey
import com.dd3boh.outertune.constants.AccountImageUrlKey
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.VisitorDataKey
import com.zionhuang.innertube.YouTube
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the signed-in account's details, including the profile image, and stores them.
 */
@Singleton
class AccountImageFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val startupFetchStarted = AtomicBoolean(false)

    /**
     * Fetches for accounts that were already signed in when the app started, so that they get a
     * profile image without signing in again. Runs at most once per process, and not at all once a
     * successful response has been stored.
     */
    suspend fun fetchOnce() {
        if (!startupFetchStarted.compareAndSet(false, true)) return
        if (context.dataStore.data.first()[AccountImageFetchedKey] == true) return
        fetch()
    }

    /**
     * Fetches and stores the account details. Leaves everything untouched when the request or the
     * parsing fails, so that the next start or sign-in retries.
     */
    suspend fun fetch() = mutex.withLock {
        val preferences = context.dataStore.data.first()
        val cookie = preferences[InnerTubeCookieKey]
        val visitorData = preferences[VisitorDataKey]
        val dataSyncId = normalizeDataSyncId(preferences[DataSyncIdKey])
        if (!isSameAccount(cookie, dataSyncId, cookie, dataSyncId)) return@withLock

        val accountInfo = YouTube.accountInfo(cookie, visitorData, dataSyncId).getOrElse {
            reportException(it)
            return@withLock
        }

        context.dataStore.edit { settings ->
            // The account may have been replaced while the request was in flight. Comparing and
            // writing inside one edit leaves no window for it to change in between.
            if (!isSameAccount(cookie, dataSyncId, settings[InnerTubeCookieKey], normalizeDataSyncId(settings[DataSyncIdKey]))) {
                return@edit
            }
            settings[AccountNameKey] = accountInfo.name
            settings[AccountEmailKey] = accountInfo.email.orEmpty()
            settings[AccountChannelHandleKey] = accountInfo.channelHandle.orEmpty()
            settings[AccountImageFetchedKey] = true
            val thumbnailUrl = accountInfo.thumbnailUrl
            if (thumbnailUrl != null) {
                settings[AccountImageUrlKey] = thumbnailUrl
            } else {
                settings.remove(AccountImageUrlKey)
            }
        }
    }
}
