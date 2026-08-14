package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.providers.MusicProvider
import com.dd3boh.outertune.providers.ProviderId

fun interface MusicProviderResolver {
    fun resolve(provider: ProviderId): MusicProvider?
}

class StaticMusicProviderRegistry(
    providers: Collection<MusicProvider>,
) : MusicProviderResolver {
    private val providers = providers.associateBy(MusicProvider::id)

    override fun resolve(provider: ProviderId): MusicProvider? = providers[provider]
}
