package net.murilo.scanner

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Voz (TTS) em pt-BR p/ avisos (radar, alertas). Throttle p/ não falar em cima. */
object Speaker {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var last = 0L

    fun init(c: Context) {
        if (tts != null) return
        tts = TextToSpeech(c.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) { try { tts?.language = Locale("pt", "BR") } catch (_: Exception) {}; ready = true }
        }
    }

    fun say(s: String, minGapMs: Long = 1500L) {
        if (!ready) return
        val now = System.currentTimeMillis()
        if (now - last < minGapMs) return
        last = now
        try { tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "spk") } catch (_: Exception) {}
    }
}
