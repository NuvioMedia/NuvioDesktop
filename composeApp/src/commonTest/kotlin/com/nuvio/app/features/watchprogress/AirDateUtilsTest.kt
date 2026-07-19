package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.time.parseEpisodeReleaseEpochMs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AirDateUtilsTest {
    @Test
    fun futureEpisodeCannotShowNewEpisodeAlert() {
        val nowEpochMs = requireNotNull(parseEpisodeReleaseEpochMs("2026-07-19T00:40:00Z"))

        val alert = calculateReleaseAlertState(
            seedLastUpdatedEpochMs = requireNotNull(parseEpisodeReleaseEpochMs("2026-07-12T00:40:00Z")),
            seedSeasonNumber = 1,
            nextSeasonNumber = 1,
            releasedIso = "2026-07-19T13:00:00Z",
            nowEpochMs = nowEpochMs,
        )

        assertFalse(alert.isReleaseAlert)
    }

    @Test
    fun newlyAiredEpisodeStillShowsNewEpisodeAlert() {
        val releaseEpochMs = requireNotNull(parseEpisodeReleaseEpochMs("2026-07-18T23:40:00Z"))

        val alert = calculateReleaseAlertState(
            seedLastUpdatedEpochMs = releaseEpochMs - 24 * 60 * 60 * 1000,
            seedSeasonNumber = 1,
            nextSeasonNumber = 1,
            releasedIso = "2026-07-18T23:40:00Z",
            nowEpochMs = requireNotNull(parseEpisodeReleaseEpochMs("2026-07-19T00:40:00Z")),
        )

        assertTrue(alert.isReleaseAlert)
    }
}
