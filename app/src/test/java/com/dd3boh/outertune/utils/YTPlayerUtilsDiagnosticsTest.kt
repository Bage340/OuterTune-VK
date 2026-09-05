package com.dd3boh.outertune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YTPlayerUtilsDiagnosticsTest {
    @Test
    fun diagnosticsIncludeEveryClientOutcome() {
        val summary = YTPlayerUtils.diagnosticsSummary(
            listOf(
                YTPlayerUtils.StreamClientDiagnostic(
                    clientName = "VISIONOS",
                    status = "UNPLAYABLE",
                    reason = "Video unavailable",
                    hasAudioFormat = false,
                    hasStreamUrl = false,
                    validationHttpCode = null,
                ),
                YTPlayerUtils.StreamClientDiagnostic(
                    clientName = "IOS",
                    status = "OK",
                    reason = null,
                    hasAudioFormat = true,
                    hasStreamUrl = true,
                    validationHttpCode = 403,
                ),
            )
        )

        assertTrue(summary.contains("VISIONOS:status=UNPLAYABLE"))
        assertTrue(summary.contains("reason=Video unavailable"))
        assertTrue(summary.contains("IOS:status=OK"))
        assertTrue(summary.contains("validate=HTTP 403"))
    }

    @Test
    fun diagnosticsFlattenAndBoundRemoteReason() {
        val marker = "secret-marker"
        val summary = YTPlayerUtils.diagnosticsSummary(
            listOf(
                YTPlayerUtils.StreamClientDiagnostic(
                    clientName = "TVHTML5",
                    status = "ERROR",
                    reason = "line one\ntoken=$marker https://example.com/?sig=$marker ${"x".repeat(200)}",
                    hasAudioFormat = false,
                    hasStreamUrl = false,
                    validationHttpCode = null,
                )
            )
        )

        assertFalse(summary.contains('\n'))
        assertFalse(summary.contains(marker))
        assertTrue(summary.contains("token=[redacted]"))
        assertTrue(summary.contains("[redacted-url]"))
        assertTrue(summary.length < 260)
    }
}
