package net.primal.android.notes.translation.device

import android.content.Context
import android.os.Build
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationSpec
import android.view.translation.Translator
import androidx.annotation.RequiresApi
import java.util.Locale
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.primal.core.utils.runCatching

class AndroidOnDeviceNoteTranslator(
    private val context: Context,
) : OnDeviceNoteTranslator {

    override suspend fun translate(text: String, targetLanguage: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching { translateOnDevice(text = text, targetLanguage = targetLanguage) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun translateOnDevice(text: String, targetLanguage: String): String? {
        val manager = context.getSystemService(TranslationManager::class.java) ?: return null
        val translationContext = TranslationContext.Builder(
            TranslationSpec(TranslationSpec.DATA_FORMAT_TEXT, Locale.forLanguageTag("und")),
            TranslationSpec(TranslationSpec.DATA_FORMAT_TEXT, Locale.forLanguageTag(targetLanguage)),
        ).build()

        val executor = Executors.newSingleThreadExecutor()
        val translator = try {
            awaitTranslator(manager, translationContext, executor)
        } catch (_: Throwable) {
            executor.shutdown()
            return null
        }
        if (translator == null) {
            executor.shutdown()
            return null
        }

        return try {
            val request = TranslationRequest.Builder()
                .setTranslationRequestValues(mutableListOf(TranslationRequestValue.forText(text)))
                .build()
            val response = suspendCancellableCoroutine { continuation ->
                translator.translate(
                    request,
                    executor,
                    Consumer { value ->
                        if (continuation.isActive) {
                            continuation.resume(value)
                        }
                    },
                )
            }
            val values = response.translationResponseValues
            if (values.size() == 0) {
                null
            } else {
                values.valueAt(0)?.text?.toString()
            }
        } finally {
            runCatching { translator.destroy() }
            executor.shutdown()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun awaitTranslator(
        manager: TranslationManager,
        translationContext: TranslationContext,
        executor: java.util.concurrent.Executor,
    ): Translator? =
        suspendCancellableCoroutine { continuation ->
            manager.createOnDeviceTranslator(
                translationContext,
                executor,
                Consumer { translator ->
                    if (continuation.isActive) {
                        continuation.resume(translator)
                    }
                },
            )
        }
}
