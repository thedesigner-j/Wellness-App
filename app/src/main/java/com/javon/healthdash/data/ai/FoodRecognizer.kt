package com.javon.healthdash.data.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.javon.healthdash.data.Prefs
import com.javon.healthdash.data.SecureKeys
import com.javon.healthdash.data.db.AiResultEntity
import com.javon.healthdash.data.db.AppDao
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface RecognitionOutcome {
    /** [resultKey] identifies the saved AI result; [cached] = shown from storage, no new call. */
    data class Success(val foods: List<RecognizedFood>, val resultKey: String, val cached: Boolean) : RecognitionOutcome
    data class Failed(val reason: String) : RecognitionOutcome
}

/**
 * Food photo recognition with Claude (the only AI that runs by default).
 *  - Structured JSON output against a strict schema, validated again in code.
 *  - One retry on invalid output, then the caller falls back to manual entry.
 *  - Each result is saved once under a hash of (prompt version, model, image); re-viewing reads it back.
 */
class FoodRecognizer(private val dao: AppDao, private val keys: SecureKeys, private val prefs: Prefs) {

    private val json = Json { encodeDefaults = true }

    suspend fun recognize(jpeg: ByteArray): RecognitionOutcome = withContext(Dispatchers.IO) {
        val model = prefs.state.value.claudeModel
        val hash = sha256(RecognitionValidator.PROMPT_VERSION.toByteArray() + model.toByteArray() + jpeg)

        dao.aiResult(hash)?.let { saved ->
            val v = RecognitionValidator.validate(saved.json)
            if (v is RecognitionValidator.Result.Valid) return@withContext RecognitionOutcome.Success(v.foods, hash, cached = true)
        }

        val apiKey = keys.get(SecureKeys.CLAUDE) ?: return@withContext RecognitionOutcome.Failed("Add a Claude API key in Settings to recognize photos")
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(90)).maxRetries(2).build()

        var lastReason = "Unknown error"
        repeat(2) { // first try + one retry
            try {
                val message = client.messages().create(buildParams(model, jpeg))
                if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
                    lastReason = "The model declined this photo"
                    return@repeat
                }
                val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
                when (val v = RecognitionValidator.validate(text)) {
                    is RecognitionValidator.Result.Valid -> {
                        dao.insertAiResult(AiResultEntity(hash, "food", model, json.encodeToString(RecognitionPayload.serializer(), RecognitionPayload(v.foods)), System.currentTimeMillis()))
                        client.close()
                        return@withContext RecognitionOutcome.Success(v.foods, hash, cached = false)
                    }
                    is RecognitionValidator.Result.Invalid -> lastReason = v.reason
                }
            } catch (e: AnthropicServiceException) {
                lastReason = "Claude API error ${e.statusCode()}"
                if (e.statusCode() in listOf(400, 401, 403, 404)) {
                    client.close()
                    return@withContext RecognitionOutcome.Failed(if (e.statusCode() == 401) "Claude API key was rejected" else lastReason)
                }
            } catch (e: Exception) {
                lastReason = e.message ?: "Network error"
            }
        }
        client.close()
        RecognitionOutcome.Failed(lastReason)
    }

    /** Previously saved result for a meal, if any (no API call). */
    suspend fun saved(resultKey: String): List<RecognizedFood>? =
        dao.aiResult(resultKey)?.let { (RecognitionValidator.validate(it.json) as? RecognitionValidator.Result.Valid)?.foods }

    private fun buildParams(model: String, jpeg: ByteArray): MessageCreateParams {
        val image = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .data(Base64.getEncoder().encodeToString(jpeg))
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .build(),
            )
            .build()
        val schema = JsonOutputFormat.Schema.builder()
            .apply { RecognitionValidator.schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
            .build()
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(RecognitionValidator.SYSTEM_PROMPT)
            .outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(schema).build()).build())
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(image),
                    ContentBlockParam.ofText(TextBlockParam.builder().text(RecognitionValidator.USER_PROMPT).build()),
                ),
            )
            // Server-side refusal fallback: if the model declines, the API retries on a fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
