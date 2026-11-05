package com.javon.healthdash.data

import com.javon.healthdash.calc.Weather
import com.javon.healthdash.data.db.AppDao
import com.javon.healthdash.data.db.WeatherEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Hourly temperatures for Las Vegas from Open-Meteo (free, no key). Only city coordinates are sent. */
class WeatherSource(private val dao: AppDao, private val http: OkHttpClient = OkHttpClient()) {
    private var lastMs = 0L

    /** Refreshes at most every 3 hours. */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastMs < 3 * 3_600_000L) return@withContext
        runCatching {
            http.newCall(Request.Builder().url(Weather.url()).build()).execute().use { r ->
                if (!r.isSuccessful) return@use
                val rows = Weather.parse(r.body.string()).map { (ms, f) -> WeatherEntity(ms, f) }
                if (rows.isNotEmpty()) { dao.upsertWeather(rows); lastMs = now }
            }
        }
    }
}
