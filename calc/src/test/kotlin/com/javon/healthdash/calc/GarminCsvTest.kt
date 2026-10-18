package com.javon.healthdash.calc

import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import org.junit.Test

class GarminCsvTest {

    private val csv = """
        Activity Type,Date,Favorite,Title,Distance,Calories,Time,Avg HR,Max HR,Avg Pace
        Running,2026-10-13 04:52:57,false,"Morning Run, easy",6.59,700,01:19:14,163,178,12:01
        Treadmill Running,2026-10-10 18:00:00,false,Treadmill,3.10,320,00:31:00,150,165,10:00
        Road Cycling,2026-10-08 07:00:00,false,Ride,25.00,900,01:30:00,140,160,--
        Strength Training,2026-10-07 07:00:00,false,Gym,0.00,200,00:45:00,110,140,--
        Walking,2026-10-06 12:00:00,false,Walk,1.50,100,00:30:00,95,110,20:00
    """.trimIndent()

    @Test fun parsesActivities() {
        val a = GarminCsv.parse(csv, ZoneOffset.UTC, distanceInKm = false)
        assertThat(a).hasSize(5)
        val run = a[0]
        assertThat(run.title).isEqualTo("Morning Run, easy") // quoted comma
        assertThat(run.sport).isEqualTo(Sport.Run)
        assertThat(run.distanceMi).isEqualTo(6.59)
        assertThat(run.durationSec).isEqualTo(4754)
        assertThat(run.avgHr).isEqualTo(163)
        assertThat(a[1].sport).isEqualTo(Sport.Run)
        assertThat(a[2].sport).isEqualTo(Sport.Ride)
        assertThat(a[3].sport).isEqualTo(Sport.Strength)
        assertThat(a[3].distanceMi).isNull()
        assertThat(a[4].sport).isNull()
    }

    @Test fun kilometresConverted() {
        val a = GarminCsv.parse(csv, ZoneOffset.UTC, distanceInKm = true)
        assertThat(a[0].distanceMi!!).isWithin(1e-3).of(6.59 / 1.609344)
    }

    @Test fun durations() {
        assertThat(GarminCsv.durationSec("01:19:14")).isEqualTo(4754)
        assertThat(GarminCsv.durationSec("31:00")).isEqualTo(1860)
        assertThat(GarminCsv.durationSec("--")).isNull()
    }

    @Test fun fitnessFromImportedRuns() {
        val now = GarminCsv.parseDate("2026-10-18 10:00:00", ZoneOffset.UTC)!!
        val runs = GarminCsv.parse(csv, ZoneOffset.UTC, false).filter { it.sport == Sport.Run }
            .map { RunSample(it.startMs, it.distanceMi!!, it.durationSec) }
        val f = Fitness.fromRuns(runs, now, 30, historyDays = 30)!!
        // (6.59 + 3.10) × 7 / 30 = 2.261 mi/week
        assertThat(f.weeklyMiles).isWithin(1e-3).of(2.261)
        assertThat(f.longestRunMi).isEqualTo(6.59)
    }

    @Test fun missingColumnsGiveNothing() {
        assertThat(GarminCsv.parse("Foo,Bar\n1,2", ZoneOffset.UTC, false)).isEmpty()
    }
}
