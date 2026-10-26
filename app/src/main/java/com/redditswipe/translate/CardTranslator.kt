package com.redditswipe.translate

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.redditswipe.data.RedditPost
import kotlinx.coroutines.tasks.await
import java.util.Locale

/** A card's title and text in the phone's language. Both are null while the model downloads or works. */
data class CardTranslation(
    /** ML Kit's code for the card's original language, such as "es". */
    val sourceLanguage: String,
    val title: String? = null,
    val body: String? = null,
) {
    val isReady: Boolean get() = title != null

    /** The original language's name in the phone's language: "Spanish", or "español" on a Spanish phone. */
    val sourceLanguageName: String
        get() = Locale.forLanguageTag(sourceLanguage).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/**
 * Translates cards into the phone's language with Google's ML Kit, on the phone: nothing is sent to a
 * server, and once a language's model (about 30 MB) has downloaded it works offline. Call from the main thread.
 */
class CardTranslator {

    private val identifier = LanguageIdentification.getClient()

    // One translator per language pair ("es>en"); each holds its model in memory.
    private val translators = mutableMapOf<String, Translator>()

    /** The phone's language as ML Kit names it, or null if ML Kit can't translate into it. */
    val targetLanguage: String?
        get() = TranslateLanguage.fromLanguageTag(Locale.getDefault().toLanguageTag().substringBefore('-'))

    val targetLanguageName: String
        get() = Locale.getDefault().getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /** The card's language if ML Kit can translate from it, else null (including when it can't tell). */
    suspend fun detectLanguage(post: RedditPost): String? {
        val sample = post.title + "\n" + post.body.take(DETECT_SAMPLE_CHARS)
        val tag = identifier.identifyLanguage(sample).await()
        if (tag == LanguageIdentification.UNDETERMINED_LANGUAGE_TAG) return null
        return TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
    }

    /** Downloads the model on first use, then translates paragraph by paragraph to keep the breaks. */
    suspend fun translate(post: RedditPost, source: String, target: String): CardTranslation {
        val translator = translators.getOrPut("$source>$target") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
            )
        }
        translator.downloadModelIfNeeded().await()
        val title = translator.translate(post.title).await()
        val body = post.body.split("\n\n").map { paragraph ->
            if (paragraph.isBlank()) paragraph else translator.translate(paragraph).await()
        }.joinToString("\n\n")
        return CardTranslation(source, title, body)
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        identifier.close()
    }

    private companion object {
        // Enough text to tell the language apart without running detection over a whole article.
        const val DETECT_SAMPLE_CHARS = 600
    }
}
