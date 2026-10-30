package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class GarminApiTest {

    private val body = """
        [
          {"activityId": 21001, "activityName": "Long Run", "startTimeLocal": "2026-10-25 07:02:11", "startTimeGMT": "2026-10-25 11:02:11",
           "beginTimestamp": 1761390131000, "activityType": {"typeId": 1, "typeKey": "running"},
           "distance": 17059.0, "duration": 6120.5, "elapsedDuration": 6400.0, "averageHR": 148.0, "maxHR": 171.0},
          {"activityId": 21002, "activityName": "Zwift", "startTimeGMT": "2026-10-24 22:00:00",
           "activityType": {"typeKey": "virtual_ride"}, "distance": 30000.0, "duration": 3600.0, "averageHR": 135},
          {"activityId": 21003, "activityName": "Gym", "startTimeLocal": "2026-10-23T18:00:00.0",
           "activityType": {"typeKey": "strength_training"}, "distance": 0.0, "duration": 2700},
          {"activityId": 21004, "activityName": "Walk", "startTimeGMT": "2026-10-22 12:00:00",
           "activityType": {"typeKey": "walking"}, "elapsedDuration": 1800.0},
          {"activityName": "no id", "startTimeGMT": "2026-10-22 12:00:00", "duration": 100},
          {"activityId": 21005, "activityName": "no start", "duration": 100},
          {"activityId": 21006, "activityName": "no duration", "startTimeGMT": "2026-10-22 12:00:00"}
        ]
    """.trimIndent()

    private val ny = ZoneId.of("America/New_York")

    @Test fun parsesActivityList() {
        val a = GarminApi.parseActivities(body, ny)
        assertThat(a.map { it.id }).containsExactly(21001L, 21002L, 21003L, 21004L).inOrder()

        val run = a[0]
        assertThat(run.startMs).isEqualTo(1761390131000L) // beginTimestamp wins
        assertThat(run.sport).isEqualTo(Sport.Run)
        assertThat(run.title).isEqualTo("Long Run")
        assertThat(run.distanceM).isEqualTo(17059.0)
        assertThat(run.durationSec).isEqualTo(6120) // timer time, not elapsed
        assertThat(run.avgHr).isEqualTo(148.0)
        assertThat(run.maxHr).isEqualTo(171.0)
        assertThat(run.indoor).isFalse()

        val ride = a[1]
        assertThat(ride.sport).isEqualTo(Sport.Ride)
        assertThat(ride.indoor).isTrue()
        assertThat(ride.startMs).isEqualTo(GarminApi.parseTime("2026-10-24 22:00:00", ZoneOffset.UTC))
        assertThat(ride.avgHr).isEqualTo(135.0) // integer JSON number
        assertThat(ride.maxHr).isNull()

        val gym = a[2]
        assertThat(gym.sport).isEqualTo(Sport.Strength)
        assertThat(gym.distanceM).isNull() // 0 distance is no distance
        // Local time only → read in the phone's zone.
        assertThat(gym.startMs).isEqualTo(java.time.LocalDateTime.of(2026, 10, 23, 18, 0).atZone(ny).toInstant().toEpochMilli())

        assertThat(a[3].sport).isNull() // walking isn't a plan sport
        assertThat(a[3].durationSec).isEqualTo(1800) // falls back to elapsed
    }

    @Test fun acceptsWrappedListAndRejectsJunk() {
        val wrapped = """{"totalCount": 1, "activityList": [{"activityId": 7, "beginTimestamp": 1000, "duration": 60, "activityType": {"typeKey": "trail_running"}}]}"""
        assertThat(GarminApi.parseActivities(wrapped, ny).single().sport).isEqualTo(Sport.Run)
        assertThat(GarminApi.parseActivities("not json", ny)).isEmpty()
        assertThat(GarminApi.parseActivities("{}", ny)).isEmpty()
        assertThat(GarminApi.parseActivities("[]", ny)).isEmpty()
        // Paging counts raw entries, so skipped (unparseable) ones still count toward a full page.
        assertThat(GarminApi.entryCount(body)).isEqualTo(7)
        assertThat(GarminApi.entryCount("oops")).isEqualTo(0)
    }

    @Test fun sportMapping() {
        assertThat(GarminApi.sportOf("treadmill_running")).isEqualTo(Sport.Run)
        assertThat(GarminApi.sportOf("road_biking")).isEqualTo(Sport.Ride)
        assertThat(GarminApi.sportOf("indoor_cycling")).isEqualTo(Sport.Ride)
        assertThat(GarminApi.sportOf("hiking")).isNull()
        assertThat(GarminApi.isIndoor("treadmill_running")).isTrue()
        assertThat(GarminApi.isIndoor("road_biking")).isFalse()
    }

    @Test fun syncRange() {
        val today = LocalDate.of(2026, 10, 29)
        // First sync: 90 days.
        assertThat(GarminApi.syncRange(today, null, ny)).isEqualTo(LocalDate.of(2026, 7, 31) to today)
        // Incremental: 3 days before the newest stored activity.
        val newest = LocalDate.of(2026, 10, 27).atTime(8, 0).atZone(ny).toInstant().toEpochMilli()
        assertThat(GarminApi.syncRange(today, newest, ny)).isEqualTo(LocalDate.of(2026, 10, 24) to today)
        // Never more than 90 days back.
        val old = LocalDate.of(2026, 1, 1).atStartOfDay(ny).toInstant().toEpochMilli()
        assertThat(GarminApi.syncRange(today, old, ny).first).isEqualTo(LocalDate.of(2026, 7, 31))
    }

    @Test fun jwtExpiry() {
        fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val token = "${b64("""{"alg":"RS256"}""")}.${b64("""{"sub":"x","exp":1761400000}""")}.sig"
        assertThat(GarminApi.jwtExpiryMs(token)).isEqualTo(1761400000000L)
        assertThat(GarminApi.jwtExpiryMs("opaque-token")).isNull()
        assertThat(GarminApi.jwtExpiryMs("a.${b64("""{"sub":"x"}""")}.c")).isNull()
    }
}

class WorkoutDedupTest {
    private data class W(val id: String, val start: Long, val sport: Sport?, val api: Boolean)

    private fun dedup(rows: List<W>) = WorkoutDedup.preferApi(rows, { it.api }, { it.start }, { it.sport }).map { it.id }

    private val t = 1_761_390_000_000L

    @Test fun apiRowReplacesHealthConnectRowOfSameActivity() {
        val rows = listOf(
            W("hc-run", t + 90_000, Sport.Run, api = false), // 1.5 min later: same run
            W("api-run", t, Sport.Run, api = true),
        )
        assertThat(dedup(rows)).containsExactly("api-run")
    }

    @Test fun keepsRowsOutsideToleranceOrOfOtherSport() {
        val rows = listOf(
            W("api-run", t, Sport.Run, api = true),
            W("hc-late", t + 2 * MINUTE_MS + 1, Sport.Run, api = false), // just over 2 min
            W("hc-ride", t + 30_000, Sport.Ride, api = false), // different sport
            W("hc-edge", t - 2 * MINUTE_MS, Sport.Run, api = false), // exactly 2 min: duplicate
        )
        assertThat(dedup(rows)).containsExactly("api-run", "hc-late", "hc-ride")
    }

    @Test fun noApiRowsMeansNoChange() {
        val rows = listOf(W("a", t, Sport.Run, false), W("b", t + 1000, Sport.Run, false))
        assertThat(dedup(rows)).containsExactly("a", "b").inOrder()
    }

    @Test fun twoApiRowsBothKept() {
        val rows = listOf(W("api1", t, Sport.Run, true), W("api2", t + 1000, Sport.Run, true), W("hc", t + 500, Sport.Run, false))
        assertThat(dedup(rows)).containsExactly("api1", "api2")
    }
}
