package com.javon.healthdash.data

import com.javon.healthdash.calc.FakeData
import com.javon.healthdash.data.db.AppDao
import com.javon.healthdash.data.db.BodyEntity
import com.javon.healthdash.data.db.GlucoseEntity
import com.javon.healthdash.data.db.LabEntity
import com.javon.healthdash.data.db.ReadingEntity
import com.javon.healthdash.data.db.SleepEntity
import com.javon.healthdash.data.db.MealEntity
import com.javon.healthdash.data.db.MealItemEntity
import com.javon.healthdash.data.db.StepsEntity
import com.javon.healthdash.data.db.WorkoutEntity

/** Loads/clears the fake data set. Every row is tagged source = "sample" so it can be removed cleanly. */
class SampleData(private val dao: AppDao) {

    suspend fun load(nowMs: Long = System.currentTimeMillis()) {
        clear()
        val d = FakeData.generate(nowMs, days = 90)
        dao.upsertSleep(d.sleep.mapIndexed { i, s -> SleepEntity("sample-sl-$i", s.window.startMs, s.window.endMs, s.deepMin, SOURCE) })
        dao.upsertReadings(d.hrvMs.mapIndexed { i, r -> ReadingEntity("sample-hrv-$i", r.timeMs, ReadingEntity.HRV, r.value, SOURCE) })
        dao.upsertReadings(d.restingHr.mapIndexed { i, r -> ReadingEntity("sample-rhr-$i", r.timeMs, ReadingEntity.RESTING_HR, r.value, SOURCE) })
        d.labA1c.forEach { (t, v) -> dao.insertLab(LabEntity(timeMs = t, kind = LabEntity.A1C, value = v, lab = "Sample lab", sample = true)) }
        dao.upsertGlucose(d.glucose.map { GlucoseEntity("sample-g-${it.timeMs}", it.timeMs, it.mgdl, SOURCE) })
        dao.upsertSteps(d.steps.mapIndexed { i, s -> StepsEntity("sample-s-$i", s.startMs, s.endMs, s.count, SOURCE) })
        dao.upsertWorkouts(d.workouts.mapIndexed { i, w -> WorkoutEntity("sample-w-$i", w.window.startMs, w.window.endMs, 56, w.title, SOURCE) })
        dao.upsertBody(d.weightsLb.mapIndexed { i, (t, v) -> BodyEntity("sample-wt-$i", t, BodyEntity.WEIGHT, v, SOURCE) })
        dao.upsertBody(d.bodyFatPct.mapIndexed { i, (t, v) -> BodyEntity("sample-bf-$i", t, BodyEntity.BODY_FAT, v, SOURCE) })
        for (m in d.meals.filter { it.timeMs <= nowMs }) {
            dao.saveMeal(
                MealEntity(timeMs = m.timeMs, photoPath = null, tags = "", usualName = null, aiResultKey = null, sample = true),
                m.foods.map { f ->
                    MealItemEntity(
                        mealId = 0, name = f.name, amount = f.grams, unit = "g", grams = f.grams, fdcId = null, fdcDescription = "Sample data",
                        carbs100 = f.per100g.carbsG, protein100 = f.per100g.proteinG, fat100 = f.per100g.fatG, fiber100 = f.per100g.fiberG, kcal100 = f.per100g.kcal,
                        aiConfidence = null,
                    )
                },
            )
        }
    }

    suspend fun clear() {
        dao.clearSampleGlucose(); dao.clearSampleSteps(); dao.clearSampleWorkouts(); dao.clearSampleBody(); dao.clearSampleMeals()
        dao.clearSampleSleep(); dao.clearSampleReadings(); dao.clearSampleLabs()
    }

    companion object {
        const val SOURCE = "sample"
    }
}
