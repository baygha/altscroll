package com.altscroll.app.data

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads Peter's replies aloud using the speech engine already on the phone.
 *
 * Deliberately not a cloud voice: Groq's TTS allows 100 requests a day, while
 * the on-device engine is free, unlimited, works offline, and starts speaking
 * immediately instead of after a round trip.
 */
class Speaker(context: Context) {

    private var ready = false
    private var engine: TextToSpeech? = null
    private var currentLocale: Locale? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                applyLocale(Locale.getDefault())
            }
        }
    }

    /**
     * Picks a voice that matches the text rather than the phone's settings.
     *
     * Peter answers in whatever language he was addressed in, so a phone set
     * to one language can easily receive a reply in another. Reading Arabic
     * script with an English voice, or the reverse, produces nonsense.
     */
    private fun localeFor(text: String): Locale = when {
        text.any { it in '؀'..'ۿ' } -> Locale("ar")
        text.any { it in 'Ѐ'..'ӿ' } -> Locale("ru")
        text.any { it in '一'..'鿿' } -> Locale.CHINESE
        else -> Locale.getDefault().takeIf { it.language !in setOf("ar", "ru", "zh") }
            ?: Locale.UK
    }

    private fun applyLocale(locale: Locale) {
        val tts = engine ?: return
        if (currentLocale == locale) return

        // isLanguageAvailable returns LANG_AVAILABLE, LANG_COUNTRY_AVAILABLE or
        // LANG_COUNTRY_VAR_AVAILABLE on success - all non-negative. The earlier
        // version of this compared against LANG_AVAILABLE alone, which is the
        // weakest of the three, so an exactly-supported locale was rejected.
        val supported = tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
        val chosen = if (supported) locale else Locale.UK
        tts.language = chosen
        currentLocale = locale
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        applyLocale(localeFor(text))
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "altscroll")
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

    fun release() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}
