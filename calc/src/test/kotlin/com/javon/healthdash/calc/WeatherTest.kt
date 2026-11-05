package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test

class WeatherTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val body = """{"hourly":{"time":["2026-11-02T17:00","2026-11-02T18:00","2026-11-02T19:00"],"temperature_2m":[74.0,72.0,70.0]}}"""

    @Test fun parsesAndAveragesOverTheRun() {
        val h = Weather.parse(body, zone)
        assertThat(h).hasSize(3)
        val start = LocalDate.of(2026, 11, 2).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        // 18:00–19:00 run touches the 18:00 and 19:00 hours → (72 + 70) / 2 = 71
        assertThat(Weather.average(h, start, start + 3_600_000L)).isEqualTo(71.0)
        assertThat(Weather.at(h, LocalDate.of(2026, 11, 2), LocalTime.of(17, 0), zone)).isEqualTo(73.0)
        // Far from any hour → null
        assertThat(Weather.average(h, start + 10 * 3_600_000L, start + 11 * 3_600_000L)).isNull()
        assertThat(Weather.parse("not json")).isEmpty()
    }
}
