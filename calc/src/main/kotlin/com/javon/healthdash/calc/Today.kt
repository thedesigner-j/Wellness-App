package com.javon.healthdash.calc

// What's on today's training plan, what's done and what's still open — shared by the in-app checklist and
// the morning / afternoon reminders. Fixed rules, no AI.

data class TodayItem(val id: Long, val sport: Sport, val kind: WorkoutKind, val minutes: Int, val distanceMi: Double?, val name: String?, val done: Boolean) {
    /** "Tempo 4.5 mi", "Strength 40 min", "Run club 6 mi". */
    val short: String get() {
        val what = when {
            kind.recurring -> name ?: "Recurring session"
            sport == Sport.Strength -> "Strength"
            kind == WorkoutKind.Race -> "Race"
            sport == Sport.Ride -> "${kind.label} ride"
            else -> kind.label
        }
        val amount = distanceMi?.let { "${TodaySummary.mi(it)} mi" } ?: "${minutes} min"
        return "$what $amount"
    }
}

data class TodaySummary(val items: List<TodayItem>) {
    val open get() = items.filter { !it.done }
    val done get() = items.filter { it.done }
    val totalMin get() = items.sumOf { it.minutes }
    val openMin get() = open.sumOf { it.minutes }
    val rest get() = items.isEmpty()
    val allDone get() = items.isNotEmpty() && open.isEmpty()

    /** "2 sessions · 1 to go", "All done ✓", "Rest day". */
    val headline: String get() = when {
        rest -> "Rest day"
        allDone -> "All done today ✓"
        else -> "${items.size} ${if (items.size == 1) "session" else "sessions"} · ${open.size} to go"
    }

    /** Morning rundown: "Tempo 4.5 mi + Strength 40 min (≈ 1 h 25 min)". Null on rest days. */
    fun morningText(): String? = if (rest) null else items.joinToString(" + ") { it.short } + " (≈ ${duration(totalMin)})"

    /** Afternoon check-in: only when something is still open. */
    fun checkInText(): String? = if (open.isEmpty()) null else "Still to do today: " + open.joinToString(" + ") { it.short } + " (≈ ${duration(openMin)})"

    /** Evening nudge in the app: 2+ sessions planned, something still open late in the day. */
    fun lateWarning(hour: Int): String? =
        if (hour >= 17 && open.isNotEmpty() && items.size >= 2) "${open.joinToString(" and ") { it.short }} still open — about ${duration(openMin)}. Do it now or move it." else null

    companion object {
        fun duration(min: Int) = if (min >= 60) "${min / 60} h${if (min % 60 > 0) " ${min % 60} min" else ""}" else "$min min"
        fun mi(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else "%.1f".format(java.util.Locale.US, x)

        /** Strength last, otherwise as planned. */
        fun of(items: List<TodayItem>) = TodaySummary(items.sortedBy { if (it.sport == Sport.Strength) 1 else 0 })
    }
}
