package com.dd3boh.outertune.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.dd3boh.outertune.constants.LyricSourcePrefKey
import com.dd3boh.outertune.constants.LyricTrimKey
import com.dd3boh.outertune.constants.MultilineLrcKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.parseLrc
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Role of a remote fetch, used to correlate log lines when several fetches run for different songs.
 */
enum class LyricsFetchRole(val log: String) {
    CURRENT("current"),
    PREFETCH("prefetch"),
    MANUAL("manual"),
}

@Singleton
class LyricsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase
) {
    private val lyricsProviders =
        listOf(LrcLibLyricsProvider, KuGouLyricsProvider, YouTubeLyricsProvider, YouTubeSubtitleLyricsProvider)
    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)

    /**
     * Per-videoId mutexes that make [fetchAndStoreRemote] single-flight: at most one fetch runs for a
     * given videoId at a time, and each fetch re-checks the database under the lock before starting.
     * Entries are never removed, so the map grows by the number of songs fetched during the process
     * lifetime; it is released when the process ends.
     */
    private val fetchMutexes = HashMap<String, Mutex>()
    private val fetchMutexesGuard = Mutex()

    private suspend fun fetchMutexFor(videoId: String): Mutex =
        fetchMutexesGuard.withLock { fetchMutexes.getOrPut(videoId) { Mutex() } }

    /**
     * Resolve lyrics for a song from the stored database row, the local .lrc file and the remote
     * providers, in an order controlled by the source preference (LyricSourcePrefKey): when local
     * lyrics are preferred the local file is tried first; otherwise the stored row wins and
     * LYRICS_NOT_FOUND resolves to null. When neither source has lyrics, a remote fetch runs and
     * the freshly stored result is returned, falling back to the local file when remote lyrics
     * are preferred but none are found.
     *
     * @param mediaMetadata song to resolve lyrics for
     */
    suspend fun getLyrics(mediaMetadata: MediaMetadata): SemanticLyrics? {
        val parserOptions = getParserOptions()
        val prefLocal = isLocalPreferred()

        val dbLyrics = database.lyrics(mediaMetadata.id).let { it.first()?.lyrics }
        if (dbLyrics != null && !prefLocal) {
            return if (dbLyrics == LYRICS_NOT_FOUND) null else parseLrc(dbLyrics, parserOptions.trim, parserOptions.multiLine)
        }

        val localLyrics: SemanticLyrics? = getLocalLyrics(mediaMetadata, parserOptions)

        // fallback to secondary provider when primary is unavailable
        if (prefLocal) {
            if (localLyrics != null) {
                return localLyrics
            }
            if (dbLyrics != null) {
                return if (dbLyrics == LYRICS_NOT_FOUND) null else parseLrc(dbLyrics, parserOptions.trim, parserOptions.multiLine)
            }
        }

        // No stored or preferred-source lyrics: fall back to a remote fetch, the slowest source.
        fetchAndStoreRemote(mediaMetadata, LyricsFetchRole.MANUAL)

        val fetched = database.lyrics(mediaMetadata.id).let { it.first()?.lyrics }
        if (fetched != null && fetched != LYRICS_NOT_FOUND) {
            return parseLrc(fetched, parserOptions.trim, parserOptions.multiLine)
        }
        return if (!prefLocal) localLyrics else null
    }

    /**
     * Resolve lyrics from remote providers and upsert the result (lyrics + provider name,
     * or LYRICS_NOT_FOUND when no provider had a match) into the database.
     *
     * Single-flight per videoId: the fetch runs under a per-videoId lock, and unless [forceRefresh]
     * is set it re-checks the database under the lock and returns without fetching when a row already
     * exists. With [forceRefresh] the database check is skipped and the result overwrites any row.
     *
     * @param role which caller started this fetch, used only for log correlation
     */
    suspend fun fetchAndStoreRemote(
        mediaMetadata: MediaMetadata,
        role: LyricsFetchRole,
        forceRefresh: Boolean = false,
    ) {
        fetchMutexFor(mediaMetadata.id).withLock {
            try {
                if (!forceRefresh && database.lyrics(mediaMetadata.id).first() != null) {
                    return
                }
                val result = getRemoteLyrics(mediaMetadata, role)
                val entity = if (result != null) {
                    LyricsEntity(id = mediaMetadata.id, lyrics = result.second, provider = result.first)
                } else {
                    LyricsEntity(id = mediaMetadata.id, lyrics = LYRICS_NOT_FOUND, provider = null)
                }
                withContext(Dispatchers.IO) {
                    database.upsert(entity)
                }
                Log.d(TAG, "saved: videoId=${mediaMetadata.id} role=${role.log} provider=${result?.first ?: "NOT_FOUND"}")
            } catch (e: CancellationException) {
                Log.d(TAG, "cancelled: videoId=${mediaMetadata.id} role=${role.log}")
                throw e
            }
        }
    }

    /**
     * Read the lyric parsing preferences (trim / multiline) shared by all resolution paths
     */
    suspend fun getParserOptions(): LrcUtils.LrcParserOptions {
        val trim = context.dataStore.get(LyricTrimKey, defaultValue = false)
        val multiline = context.dataStore.get(MultilineLrcKey, defaultValue = true)
        return LrcUtils.LrcParserOptions(trim, multiline, "Unable to parse lyrics")
    }

    suspend fun isLocalPreferred(): Boolean = context.dataStore.get(LyricSourcePrefKey, true)

    /**
     * Lookup lyrics from remote providers.
     *
     * Every enabled provider runs at once. Results are judged as they arrive: the first synced
     * result wins immediately and the remaining providers are cancelled; an unsynced result is
     * kept as a fallback and used only if no synced result arrives before every provider finishes
     * or the overall timeout is reached. Each provider has an individual timeout; the whole
     * resolution is bounded by an overall cap.
     *
     * @return provider name to raw lyrics text, or null if no enabled provider had a usable match
     */
    private suspend fun getRemoteLyrics(mediaMetadata: MediaMetadata, role: LyricsFetchRole): Pair<String, String>? {
        val artistName = mediaMetadata.artists
            .filter { it.id != null }
            .joinToString { it.name.removeSuffix(" - Topic") }
            .ifEmpty { mediaMetadata.artists.joinToString { it.name } }
        val start = System.currentTimeMillis()
        Log.d(TAG, "start: videoId=${mediaMetadata.id} role=${role.log} title=\"${mediaMetadata.title}\" artist=\"${artistName}\"")

        lyricsProviders.filterNot { it.isEnabled(context) }.forEach { provider ->
            Log.d(TAG, "${provider.name} SKIPPED (disabled) videoId=${mediaMetadata.id} role=${role.log}")
        }
        val enabled = lyricsProviders.filter { it.isEnabled(context) }
        if (enabled.isEmpty()) {
            Log.d(TAG, "end: not found videoId=${mediaMetadata.id} role=${role.log} total=0ms (no enabled providers)")
            return null
        }

        val parserOptions = getParserOptions()

        return coroutineScope {
            val channel = Channel<ProviderOutcome>(Channel.UNLIMITED)
            val fetchJobs = enabled.map { provider ->
                launch {
                    val t0 = System.currentTimeMillis()
                    val result = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        provider.getLyrics(
                            mediaMetadata.id,
                            mediaMetadata.title,
                            artistName,
                            mediaMetadata.duration
                        )
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    val raw: String? = when {
                        result == null -> {
                            Log.d(TAG, "${provider.name} FAILURE in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}: timeout after ${PROVIDER_TIMEOUT_MS}ms")
                            null
                        }

                        else -> result.fold(
                            onSuccess = {
                                Log.d(TAG, "${provider.name} SUCCESS in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}")
                                it
                            },
                            onFailure = {
                                // runCatching in the providers also captures CancellationException. An outer
                                // cancellation (this job no longer active) must propagate; a provider-level
                                // timeout leaves the job active and is reported as an ordinary failure.
                                if (it is CancellationException) {
                                    if (!isActive) throw it
                                    Log.d(TAG, "${provider.name} FAILURE in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}: ${it.message}")
                                    null
                                } else {
                                    Log.d(TAG, "${provider.name} FAILURE in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}: ${it.message}")
                                    reportException(it)
                                    null
                                }
                            }
                        )
                    }
                    channel.send(ProviderOutcome(provider.name, raw))
                }
            }
            // Close the channel once every provider has reported so exhaustion is detected promptly
            // instead of waiting for the overall timeout.
            val closer = launch {
                fetchJobs.joinAll()
                channel.close()
            }

            var adopted: Pair<String, String>? = null
            var heldUnsynced: Pair<String, String>? = null
            val deadline = start + OVERALL_TIMEOUT_MS

            try {
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val outcome = withTimeoutOrNull(remaining) {
                        channel.receiveCatching().getOrNull()
                    } ?: break // overall timeout, or every provider has reported
                    val raw = outcome.raw ?: continue
                    when (parseLrc(raw, parserOptions.trim, parserOptions.multiLine)) {
                        is SemanticLyrics.SyncedLyrics -> {
                            adopted = outcome.providerName to raw
                            break
                        }

                        is SemanticLyrics.UnsyncedLyrics -> {
                            if (heldUnsynced == null) {
                                heldUnsynced = outcome.providerName to raw
                            }
                        }

                        null -> {} // unparseable, ignore
                    }
                }
            } finally {
                fetchJobs.forEach { it.cancel() }
                closer.cancel()
                channel.close()
            }

            val synced = adopted != null
            val finalAdopted = adopted ?: heldUnsynced
            val totalMs = System.currentTimeMillis() - start
            if (finalAdopted != null) {
                Log.d(TAG, "adopted: videoId=${mediaMetadata.id} role=${role.log} provider=${finalAdopted.first} synced=$synced total=${totalMs}ms")
                finalAdopted
            } else {
                Log.d(TAG, "end: not found videoId=${mediaMetadata.id} role=${role.log} total=${totalMs}ms")
                null
            }
        }
    }

    /**
     * Lookup lyrics from local disk (.lrc) file
     */
    fun getLocalLyrics(
        mediaMetadata: MediaMetadata,
        parserOptions: LrcUtils.LrcParserOptions
    ): SemanticLyrics? {
        if (LocalLyricsProvider.isEnabled(context) && mediaMetadata.localPath != null) {
            return LocalLyricsProvider.getLyricsNew(
                mediaMetadata.localPath,
                parserOptions
            )
        }

        return null
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        callback: (LyricsResult) -> Unit,
    ) {
        val cacheKey = "$songArtists-$songTitle".replace(" ", "")
        cache.get(cacheKey)?.let { results ->
            results.forEach {
                callback(it)
            }
            return
        }
        val allResult = mutableListOf<LyricsResult>()
        lyricsProviders.forEach { provider ->
            if (provider.isEnabled(context)) {
                provider.getAllLyrics(mediaId, songTitle, songArtists, duration) { lyrics ->
                    val result = LyricsResult(provider.name, lyrics)
                    allResult += result
                    callback(result)
                }
            }
        }
        cache.put(cacheKey, allResult)
    }

    companion object {
        private const val TAG = "LyricsHelper"
        private const val MAX_CACHE_SIZE = 3

        /** Per-provider timeout for a single remote lookup. */
        private const val PROVIDER_TIMEOUT_MS = 8000L

        /** Upper bound for resolving lyrics across all providers of a single song. */
        private const val OVERALL_TIMEOUT_MS = 12000L
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

/**
 * Outcome reported by a single provider during parallel resolution.
 *
 * @param raw the raw lyrics text, or null when the provider failed or timed out
 */
private data class ProviderOutcome(
    val providerName: String,
    val raw: String?,
)
