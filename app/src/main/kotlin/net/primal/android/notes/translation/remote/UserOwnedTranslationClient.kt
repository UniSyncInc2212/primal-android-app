package net.primal.android.notes.translation.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.primal.android.notes.translation.NetworkTranslationRoute
import net.primal.android.notes.translation.NoteTranslationConfig
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.CommonJson
import net.primal.core.utils.serialization.CommonJsonImplicitNulls

fun interface RemoteNoteTranslator {
    suspend fun translate(
        route: NetworkTranslationRoute,
        text: String,
        targetLanguage: String,
    ): String
}

class UserOwnedTranslationClient(
    private val httpClient: HttpClient,
) : RemoteNoteTranslator {
    override suspend fun translate(
        route: NetworkTranslationRoute,
        text: String,
        targetLanguage: String,
    ): String {
        val translated = when (route) {
            is NetworkTranslationRoute.LibreTranslate -> translateLibre(
                route = route,
                text = text,
                targetLanguage = targetLanguage,
            )

            is NetworkTranslationRoute.DeepL -> translateDeepL(
                route = route,
                text = text,
                targetLanguage = targetLanguage,
            )

            is NetworkTranslationRoute.Google -> translateGoogle(
                route = route,
                text = text,
                targetLanguage = targetLanguage,
            )
        }
        if (translated.isBlank()) {
            throw IOException("Translation provider returned empty text.")
        }
        return translated
    }

    private suspend fun translateLibre(
        route: NetworkTranslationRoute.LibreTranslate,
        text: String,
        targetLanguage: String,
    ): String {
        val body = buildJsonObject {
            put("q", text)
            put("source", "auto")
            put("target", targetLanguage)
            put("format", "text")
            val apiKey = route.apiKey
            if (!apiKey.isNullOrBlank()) {
                put("api_key", apiKey)
            }
        }
        val payload = postJson(url = "${route.endpoint}/translate", json = body.toString())
        val parsed = decode<LibreTranslateResponse>(payload)
        return parsed.translatedText?.ifBlank { null }
            ?: parsed.translation?.ifBlank { null }
            ?: parsed.text.orEmpty()
    }

    private suspend fun translateDeepL(
        route: NetworkTranslationRoute.DeepL,
        text: String,
        targetLanguage: String,
    ): String {
        val body = buildJsonObject {
            putJsonArray("text") { add(kotlinx.serialization.json.JsonPrimitive(text)) }
            put("target_lang", toDeepLLanguage(targetLanguage))
        }
        val payload = postJson(
            url = route.endpoint,
            json = body.toString(),
            headers = mapOf("Authorization" to "DeepL-Auth-Key ${route.apiKey}"),
        )
        return decode<DeepLResponse>(payload).translations?.firstOrNull()?.text.orEmpty()
    }

    private suspend fun translateGoogle(
        route: NetworkTranslationRoute.Google,
        text: String,
        targetLanguage: String,
    ): String {
        val body = CommonJsonImplicitNulls.encodeToString(
            GoogleTranslateRequest(
                q = listOf(text),
                target = targetLanguage,
                format = "text",
            ),
        )
        val payload = postJson(
            url = NoteTranslationConfig.GOOGLE_TRANSLATE_ENDPOINT,
            json = body,
            parameters = mapOf("key" to route.apiKey),
        )
        return decode<GoogleTranslateResponse>(payload).data?.translations?.firstOrNull()?.translatedText.orEmpty()
    }

    private suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        parameters: Map<String, String> = emptyMap(),
    ): String {
        val response = httpClient.post(url) {
            headers.forEach { (name, value) -> header(name, value) }
            parameters.forEach { (name, value) -> parameter(name, value) }
            setBody(TextContent(json, ContentType.Application.Json))
        }
        val payload = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IOException("Translation HTTP ${response.status.value}")
        }
        return payload
    }

    private inline fun <reified T> decode(payload: String): T {
        return runCatching { CommonJson.decodeFromString<T>(payload) }.getOrElse {
            throw IOException("Unable to parse translation response.")
        }
    }
}

internal fun toDeepLLanguage(code: String): String {
    val normalized = code.trim()
    return when {
        normalized.equals("zh-TW", ignoreCase = true) ||
            normalized.equals("zh-Hant", ignoreCase = true) -> "ZH-HANT"
        normalized.equals("zh", ignoreCase = true) ||
            normalized.equals("zh-CN", ignoreCase = true) ||
            normalized.equals("zh-Hans", ignoreCase = true) -> "ZH-HANS"
        normalized.equals("pt-PT", ignoreCase = true) -> "PT-PT"
        normalized.equals("pt", ignoreCase = true) ||
            normalized.equals("pt-BR", ignoreCase = true) -> "PT-BR"
        normalized.equals("en-GB", ignoreCase = true) -> "EN-GB"
        normalized.equals("en", ignoreCase = true) ||
            normalized.equals("en-US", ignoreCase = true) -> "EN"
        else -> normalized.uppercase()
    }
}

@Serializable
private data class LibreTranslateResponse(
    val translatedText: String? = null,
    val translation: String? = null,
    val text: String? = null,
)

@Serializable
private data class DeepLResponse(
    val translations: List<DeepLTranslation>? = null,
)

@Serializable
private data class DeepLTranslation(
    val text: String? = null,
)

@Serializable
private data class GoogleTranslateRequest(
    val q: List<String>,
    val target: String,
    val format: String,
)

@Serializable
private data class GoogleTranslateResponse(
    val data: GoogleTranslateData? = null,
)

@Serializable
private data class GoogleTranslateData(
    val translations: List<GoogleTranslation>? = null,
)

@Serializable
private data class GoogleTranslation(
    val translatedText: String? = null,
)
