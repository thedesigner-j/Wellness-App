package com.javon.healthdash.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.javon.healthdash.BuildConfig
import com.javon.healthdash.ui.theme.ThemeMode
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-secret app settings. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class State(
        val onboardingDone: Boolean,
        val sampleData: Boolean,
        val themeMode: ThemeMode,
        val aiRewording: Boolean,
        val foodRecognition: Boolean,
        val walkNudge: Boolean,
        val trainingMorning: Boolean,
        val trainingCheckIn: Boolean,
        val glucoseSource: String?,
        val claudeModel: String,
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun read() = State(
        onboardingDone = sp.getBoolean("onboardingDone", false),
        sampleData = sp.getBoolean("sampleData", false),
        themeMode = ThemeMode.valueOf(sp.getString("themeMode", ThemeMode.Dark.name)!!),
        aiRewording = sp.getBoolean("aiRewording", false),
        foodRecognition = sp.getBoolean("foodRecognition", true),
        walkNudge = sp.getBoolean("walkNudge", true),
        trainingMorning = sp.getBoolean("trainingMorning", true),
        trainingCheckIn = sp.getBoolean("trainingCheckIn", true),
        glucoseSource = sp.getString("glucoseSource", null),
        claudeModel = sp.getString("claudeModel", null) ?: BuildConfig.DEFAULT_CLAUDE_MODEL,
    )

    fun update(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        _state.value = read()
    }

    fun setOnboardingDone(v: Boolean) = update { putBoolean("onboardingDone", v) }
    fun setSampleData(v: Boolean) = update { putBoolean("sampleData", v) }
    fun setThemeMode(v: ThemeMode) = update { putString("themeMode", v.name) }
    fun setAiRewording(v: Boolean) = update { putBoolean("aiRewording", v) }
    fun setFoodRecognition(v: Boolean) = update { putBoolean("foodRecognition", v) }
    fun setWalkNudge(v: Boolean) = update { putBoolean("walkNudge", v) }
    fun setTrainingMorning(v: Boolean) = update { putBoolean("trainingMorning", v) }
    fun setTrainingCheckIn(v: Boolean) = update { putBoolean("trainingCheckIn", v) }
    fun setGlucoseSource(v: String?) = update { putString("glucoseSource", v) }
    fun setClaudeModel(v: String) = update { putString("claudeModel", v.trim()) }

    // Recurring sessions (e.g. a weekly run club) that every plan is built around.
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val _recurring = MutableStateFlow(readRecurring())
    val recurring: StateFlow<List<RecurringSession>> = _recurring.asStateFlow()

    private fun readRecurring(): List<RecurringSession> =
        sp.getString("recurring", null)?.let { runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RecurringSession.serializer()), it) }.getOrNull() } ?: emptyList()

    fun setRecurring(list: List<RecurringSession>) {
        sp.edit().putString("recurring", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(RecurringSession.serializer()), list)).apply()
        _recurring.value = list
    }
}

@kotlinx.serialization.Serializable
data class RecurringSession(
    val id: Long,
    val name: String,
    /** java.time.DayOfWeek value, 1 = Monday. */
    val day: Int,
    val sport: String,
    val distanceMi: Double?,
    val durationMin: Int,
    val intensity: String,
    val keepInTaper: Boolean = true,
) {
    fun toFixed() = com.javon.healthdash.calc.FixedSession(
        java.time.DayOfWeek.of(day), com.javon.healthdash.calc.Sport.valueOf(sport), name, distanceMi, durationMin,
        com.javon.healthdash.calc.Intensity.valueOf(intensity), keepInTaper,
    )
}

/**
 * API keys, encrypted with an AES-GCM key that never leaves the Android Keystore.
 * Values are stored as base64(iv + ciphertext) in a private preferences file.
 */
class SecureKeys(context: Context) {
    private val sp = context.getSharedPreferences("secure_keys", Context.MODE_PRIVATE)
    private val alias = "healthdash_api_keys"

    private val _present = MutableStateFlow(presentNow())
    val present: StateFlow<Map<String, Boolean>> = _present.asStateFlow()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            sp.edit().remove(name).apply()
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            sp.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        }
        _present.value = presentNow()
    }

    fun get(name: String): String? {
        val stored = sp.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun has(name: String) = sp.contains(name)

    private fun presentNow() = mapOf(CLAUDE to has(CLAUDE), USDA to has(USDA), GARMIN to has(GARMIN))

    /** Last 4 characters, for display only. */
    fun hint(name: String): String? = get(name)?.takeLast(4)

    companion object {
        const val CLAUDE = "claude"
        const val USDA = "usda"
        /** Garmin Connect personal API key, pasted by the user. */
        const val GARMIN = "garmin"
        /** Short-lived access token exchanged from [GARMIN] (cached so each sync doesn't ask for a new one). */
        const val GARMIN_TOKEN = "garmin_token"
    }
}
