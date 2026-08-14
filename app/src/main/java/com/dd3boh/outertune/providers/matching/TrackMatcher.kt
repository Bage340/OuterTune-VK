/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers.matching

import com.dd3boh.outertune.providers.domain.ProviderTrackKey
import com.dd3boh.outertune.providers.domain.RemoteTrack
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TrackVariant {
    REMIX,
    LIVE,
    SPED_UP,
    SLOWED,
    INSTRUMENTAL,
    REMASTER,
    COVER,
}

enum class MatchSignal {
    EXISTING_PROVIDER_MAPPING,
    EXACT_PROVIDER_ID,
    NORMALIZED_ARTIST_TITLE,
    YO_E_HEURISTIC,
    DURATION_WITHIN_TOLERANCE,
    DURATION_CONFLICT,
    NORMALIZED_ALBUM,
    SAME_TRACK_VARIANT,
    VARIANT_MISMATCH,
}

enum class MatchConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

data class NormalizedTitle(
    val comparableText: String,
    val looseComparableText: String,
    val variants: Set<TrackVariant>,
)

data class NormalizedTrackMetadata(
    val title: NormalizedTitle,
    val artists: List<String>,
    val looseArtists: List<String>,
    val album: String?,
    val looseAlbum: String?,
)

/** Pure, locale-independent metadata normalization used by matching and tests. */
object TrackNormalizer {
    fun normalize(track: RemoteTrack): NormalizedTrackMetadata {
        val artists = track.artists
            .map(::normalizeArtist)
            .filter(String::isNotEmpty)
        return NormalizedTrackMetadata(
            title = normalizeTitle(track.title),
            artists = artists,
            looseArtists = artists.map(::replaceYoWithE),
            album = track.album?.let(::normalizePlainText)?.takeIf(String::isNotEmpty),
            looseAlbum = track.album
                ?.let(::normalizePlainText)
                ?.let(::replaceYoWithE)
                ?.takeIf(String::isNotEmpty),
        )
    }

    fun normalizeTitle(value: String): NormalizedTitle {
        val unicode = normalizeUnicode(value)
        val variants = TrackVariant.entries
            .filterTo(linkedSetOf()) { variant -> variantPatterns.getValue(variant).containsMatchIn(unicode) }

        val comparable = unicode
            .replace(bracketedFeaturing, " ")
            .replace(inlineFeaturing, " ")
            .replace(bracketedDecoration, " ")
            .replace(bracketedVariant, " ")
            .replace(trailingVariant, " ")
            .let(::normalizePlainText)

        return NormalizedTitle(
            comparableText = comparable,
            looseComparableText = replaceYoWithE(comparable),
            variants = variants,
        )
    }

    fun normalizeArtist(value: String): String = normalizeUnicode(value)
        .replace(bracketedFeaturing, " ")
        .replace(inlineFeaturing, " ")
        .let(::normalizePlainText)

    fun normalizePlainText(value: String): String = normalizeUnicode(value)
        .replace(punctuationOrSymbol, " ")
        .replace(repeatedWhitespace, " ")
        .trim()

    /** `ё`/`е` is intentionally a secondary representation, never the primary key. */
    fun replaceYoWithE(value: String): String = value.replace('ё', 'е')

    private fun normalizeUnicode(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private val repeatedWhitespace = Regex("""\s+""")
    private val punctuationOrSymbol = Regex("""[\p{P}\p{S}]+""")
    private val bracketedFeaturing = Regex(
        """(?i)[\(\[\{]\s*(?:feat(?:uring)?|ft)\.?\s+[^\)\]\}]*[\)\]\}]"""
    )
    private val inlineFeaturing = Regex(
        """(?i)\s+(?:feat(?:uring)?|ft)\.?\s+.+$"""
    )
    private val bracketedDecoration = Regex(
        """(?i)[\(\[\{]\s*(?:(?:official|original)\s+)?(?:audio|video|music\s+video|lyric(?:s)?(?:\s+video)?|visuali[sz]er|hq|hd)\s*[\)\]\}]"""
    )
    private val variantExpression =
        """remix(?:ed)?|live(?:\s+at[^\)\]\}]*)?|sped\s+up|slowed(?:\s+down)?|instrumental|remaster(?:ed)?(?:\s+\d{4})?|cover"""
    private val bracketedVariant = Regex(
        """(?i)[\(\[\{]\s*(?:$variantExpression)\s*[\)\]\}]"""
    )
    private val trailingVariant = Regex(
        """(?i)\s*(?:[-–—:]\s*)?(?:$variantExpression)\s*$"""
    )
    private val variantPatterns = mapOf(
        TrackVariant.REMIX to Regex("""(?i)\bremix(?:ed)?\b"""),
        TrackVariant.LIVE to Regex("""(?i)\blive\b"""),
        TrackVariant.SPED_UP to Regex("""(?i)\bsped\s+up\b"""),
        TrackVariant.SLOWED to Regex("""(?i)\bslowed(?:\s+down)?\b"""),
        TrackVariant.INSTRUMENTAL to Regex("""(?i)\binstrumental\b"""),
        TrackVariant.REMASTER to Regex("""(?i)\bremaster(?:ed)?\b"""),
        TrackVariant.COVER to Regex("""(?i)\bcover\b"""),
    )
}

data class TrackMatch(
    val candidate: RemoteTrack,
    val confidence: MatchConfidence,
    val score: Double,
    val signals: List<MatchSignal>,
) {
    val canAutoLink: Boolean
        get() = confidence == MatchConfidence.HIGH
}

data class TrackMatchResult(
    val rankedMatches: List<TrackMatch>,
) {
    val best: TrackMatch?
        get() = rankedMatches.firstOrNull()

    val automaticMatch: TrackMatch?
        get() = best?.takeIf(TrackMatch::canAutoLink)
}

/**
 * Ordered matching strategy:
 * existing mapping -> exact provider ID -> normalized artist/title -> duration -> album.
 */
class TrackMatcher(
    private val durationToleranceSeconds: Int = DEFAULT_DURATION_TOLERANCE_SECONDS,
    private val highConfidenceThreshold: Double = DEFAULT_HIGH_CONFIDENCE_THRESHOLD,
    private val mediumConfidenceThreshold: Double = DEFAULT_MEDIUM_CONFIDENCE_THRESHOLD,
) {
    init {
        require(durationToleranceSeconds >= 0)
        require(highConfidenceThreshold in 0.0..1.0)
        require(mediumConfidenceThreshold in 0.0..highConfidenceThreshold)
    }

    fun rank(
        source: RemoteTrack,
        candidates: List<RemoteTrack>,
        mappedCandidateKeys: Set<ProviderTrackKey> = emptySet(),
    ): TrackMatchResult = TrackMatchResult(
        candidates
            .map { candidate -> evaluate(source, candidate, candidate.key in mappedCandidateKeys) }
            .sortedWith(
                compareByDescending<TrackMatch> { it.score }
                    .thenBy { it.candidate.provider.ordinal }
                    .thenBy { it.candidate.remoteId }
            )
    )

    fun evaluate(
        source: RemoteTrack,
        candidate: RemoteTrack,
        hasExistingMapping: Boolean = false,
    ): TrackMatch {
        if (hasExistingMapping) {
            return TrackMatch(
                candidate = candidate,
                confidence = MatchConfidence.HIGH,
                score = 1.0,
                signals = listOf(MatchSignal.EXISTING_PROVIDER_MAPPING),
            )
        }

        if (source.key == candidate.key) {
            return TrackMatch(
                candidate = candidate,
                confidence = MatchConfidence.HIGH,
                score = 0.99,
                signals = listOf(MatchSignal.EXACT_PROVIDER_ID),
            )
        }

        val left = TrackNormalizer.normalize(source)
        val right = TrackNormalizer.normalize(candidate)
        val signals = mutableListOf<MatchSignal>()
        var score = 0.0

        val exactTitle = left.title.comparableText.isNotEmpty() &&
            left.title.comparableText == right.title.comparableText
        val looseTitle = !exactTitle && left.title.looseComparableText.isNotEmpty() &&
            left.title.looseComparableText == right.title.looseComparableText
        val exactArtistStrength = artistMatchStrength(left, right, useLoose = false)
        val looseArtistStrength = artistMatchStrength(left, right, useLoose = true)
        val artistStrength = if (looseTitle) {
            looseArtistStrength
        } else {
            max(exactArtistStrength, looseArtistStrength)
        }
        val usedYoHeuristic = looseTitle ||
            (exactArtistStrength == 0.0 && looseArtistStrength > 0.0)

        if ((exactTitle || looseTitle) && artistStrength > 0.0) {
            score += when {
                artistStrength >= 1.0 -> 0.72
                artistStrength >= 0.85 -> 0.68
                else -> 0.63
            }
            if (looseTitle) score -= 0.06
            signals += MatchSignal.NORMALIZED_ARTIST_TITLE
            if (usedYoHeuristic) {
                signals += MatchSignal.YO_E_HEURISTIC
            }
        }

        var hardDurationConflict = false
        val leftDuration = source.durationSeconds?.takeIf { it >= 0 }
        val rightDuration = candidate.durationSeconds?.takeIf { it >= 0 }
        if (leftDuration != null && rightDuration != null) {
            val difference = abs(leftDuration - rightDuration)
            if (difference <= durationToleranceSeconds) {
                score += 0.15
                signals += MatchSignal.DURATION_WITHIN_TOLERANCE
            } else {
                score -= 0.12
                signals += MatchSignal.DURATION_CONFLICT
                hardDurationConflict = difference > max(
                    HARD_DURATION_CONFLICT_SECONDS,
                    durationToleranceSeconds * 4,
                )
            }
        }

        if (albumsMatch(left, right)) {
            score += 0.08
            signals += MatchSignal.NORMALIZED_ALBUM
        }

        val variantMismatch = left.title.variants != right.title.variants &&
            (left.title.variants.isNotEmpty() || right.title.variants.isNotEmpty())
        if (variantMismatch) {
            score = min(score, SAFEGUARD_SCORE_CAP)
            signals += MatchSignal.VARIANT_MISMATCH
        } else if (left.title.variants.isNotEmpty()) {
            score += 0.04
            signals += MatchSignal.SAME_TRACK_VARIANT
        }

        if (hardDurationConflict) {
            score = min(score, SAFEGUARD_SCORE_CAP)
        }

        val boundedScore = score.coerceIn(0.0, 1.0)
        val confidence = when {
            variantMismatch || hardDurationConflict -> MatchConfidence.LOW
            boundedScore >= highConfidenceThreshold -> MatchConfidence.HIGH
            boundedScore >= mediumConfidenceThreshold -> MatchConfidence.MEDIUM
            else -> MatchConfidence.LOW
        }
        return TrackMatch(
            candidate = candidate,
            confidence = confidence,
            score = boundedScore,
            signals = signals,
        )
    }

    private fun artistMatchStrength(
        left: NormalizedTrackMetadata,
        right: NormalizedTrackMetadata,
        useLoose: Boolean,
    ): Double {
        val leftArtists = if (useLoose) left.looseArtists else left.artists
        val rightArtists = if (useLoose) right.looseArtists else right.artists
        if (leftArtists.isEmpty() || rightArtists.isEmpty()) return 0.0
        if (leftArtists.toSet() == rightArtists.toSet()) return 1.0
        if (leftArtists.first() == rightArtists.first()) return 0.9
        if (leftArtists.any(rightArtists::contains)) return 0.75
        return 0.0
    }

    private fun albumsMatch(
        left: NormalizedTrackMetadata,
        right: NormalizedTrackMetadata,
    ): Boolean {
        val leftAlbum = left.album ?: return false
        val rightAlbum = right.album ?: return false
        return leftAlbum == rightAlbum || left.looseAlbum == right.looseAlbum
    }

    companion object {
        const val DEFAULT_DURATION_TOLERANCE_SECONDS = 3
        const val DEFAULT_HIGH_CONFIDENCE_THRESHOLD = 0.85
        const val DEFAULT_MEDIUM_CONFIDENCE_THRESHOLD = 0.60
        const val HARD_DURATION_CONFLICT_SECONDS = 15
        const val SAFEGUARD_SCORE_CAP = 0.49
    }
}
