package com.javon.healthdash

import android.content.Context
import com.javon.healthdash.calc.GlucosePoint
import com.javon.healthdash.calc.HOUR_MS
import com.javon.healthdash.calc.Reading
import com.javon.healthdash.calc.SleepSession
import com.javon.healthdash.calc.StepInterval
import com.javon.healthdash.calc.TimeWindow
import com.javon.healthdash.data.db.ReadingEntity
import com.javon.healthdash.data.HealthConnectSource
import com.javon.healthdash.data.Photos
import com.javon.healthdash.data.Prefs
import com.javon.healthdash.data.SampleData
import com.javon.healthdash.data.SecureKeys
import com.javon.healthdash.data.TargetsRepository
import com.javon.healthdash.data.UsdaClient
import com.javon.healthdash.data.ai.FoodRecognizer
import com.javon.healthdash.data.db.AppDatabase
import com.javon.healthdash.data.db.BodyEntity
import com.javon.healthdash.domain.Analysis
import com.javon.healthdash.domain.DashboardState
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Manual dependency container (one per process). */
class AppContainer(context: Context) {
    val db = AppDatabase.build(context)
    val dao = db.dao()
    val prefs = Prefs(context)
    val keys = SecureKeys(context)
    val targets = TargetsRepository(dao)
    val health = HealthConnectSource(context, dao)
    val sample = SampleData(dao)
    val usda = UsdaClient(keys)
    val recognizer = FoodRecognizer(dao, keys, prefs)
    val photos = Photos(context)
    val training = com.javon.healthdash.data.TrainingRepository(dao)

    /** Full sync on first run (90 days of history), then the last 3 days to pick up late data. */
    suspend fun syncNow() {
        val first = prefs.state.value.let { !it.onboardingDone } || dao.glucoseSources(0).none { it != SampleData.SOURCE }
        health.sync(days = if (first) 90 else 3)
    }

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    /** Everything the dashboard screens show, recomputed whenever the database changes (and each minute). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dashboard: Flow<DashboardState> = ticker.flatMapLatest { now ->
        val from = now - 90 * 24 * HOUR_MS
        val glucose = combine(dao.glucose(from, now + HOUR_MS), prefs.state) { rows, p ->
            rows.filter { p.glucoseSource == null || it.source == p.glucoseSource || it.source == SampleData.SOURCE }
                .map { GlucosePoint(it.timeMs, it.mgdl) }
        }
        val steps = dao.steps(from, now + HOUR_MS)
        val workouts = dao.workouts(from, now + HOUR_MS)
        val body = combine(dao.recentBody(BodyEntity.WEIGHT), dao.recentBody(BodyEntity.BODY_FAT)) { w, f -> w to f }
        val meta = combine(targets.values, dao.syncStates()) { t, s -> t to s }
        val context = combine(dao.sleep(from), dao.readings(from), dao.labs()) { sl, rd, lb -> Triple(sl, rd, lb) }
        combine(glucose, steps, workouts, dao.meals(), combine(body, meta, context) { b, m, cx -> Triple(b, m, cx) }) { g, s, w, meals, (bodyPair, metaPair, cx) ->
            val (sl, rd, lb) = cx
            fun kind(k: String) = rd.filter { it.kind == k }.map { Reading(it.timeMs, it.value) }
            Analysis.build(
                nowMs = now,
                zone = ZoneId.systemDefault(),
                targets = TargetsRepository.toTargets(metaPair.first),
                targetValues = metaPair.first,
                glucose = g,
                steps = s.map { StepInterval(it.startMs, it.endMs, it.count) },
                workouts = w,
                meals = meals,
                weights = bodyPair.first,
                bodyFat = bodyPair.second,
                syncStates = metaPair.second,
            ).copy(
                sleep = sl.map { SleepSession(TimeWindow(it.startMs, it.endMs), it.deepMin) },
                hrv = kind(ReadingEntity.HRV),
                restingHr = kind(ReadingEntity.RESTING_HR),
                vo2 = kind(ReadingEntity.VO2_MAX),
                labs = lb,
            )
        }
    }.flowOn(Dispatchers.Default)
}
