/*
 * Copyright (C) 2026 OuterTune VK contributors
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.di

import android.content.Context
import com.dd3boh.outertune.auth.vk.OfficialVkAuthManager
import com.dd3boh.outertune.auth.vk.VkAuthManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VkAuthModule {
    @Provides
    @Singleton
    fun provideVkAuthManager(
        @ApplicationContext context: Context,
    ): VkAuthManager = OfficialVkAuthManager(context)
}
