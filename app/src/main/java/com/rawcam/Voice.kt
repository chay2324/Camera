package com.rawcam

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Push-to-talk speech input. Uses ONLY the phone's on-device recognizer, never the cloud one,
 * so your voice is not sent to Google or anyone else. If the phone has no offline recognizer
 * (Android 12+ with an offline language pack) it says so instead of falling back.
 */
class VoiceInput(private val ctx: Context) {
    private var rec: SpeechRecognizer? = null

    fun available(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    /** Must be called on the main thread. [onText] gets alternatives, best first. */
    fun listen(onHeard: (List<String>) -> Unit, onFail: (String) -> Unit, onStart: () -> Unit) {
        if (!available()) {
            onFail("Offline speech recognition isn't available on this phone")
            return
        }
        stop()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { onStart() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onError(code: Int) {
                onFail(when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "Offline language pack missing (Settings > Voice input)"
                    else -> "Speech error ($code)"
                })
            }
            override fun onResults(b: Bundle?) {
                val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (list.isEmpty()) onFail("I didn't hear anything") else onHeard(list)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 4)
        }
        r.startListening(i)
    }

    fun stop() {
        try { rec?.cancel(); rec?.destroy() } catch (_: Exception) {}
        rec = null
    }
}

/** Spoken replies through the phone's text-to-speech engine (optional, can be muted). */
class Speaker(ctx: Context) {
    private var ready = false
    @Volatile var enabled = true
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                try { tts?.language = Locale.getDefault() } catch (_: Exception) {}
            }
        }
    }

    fun say(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rawcam")
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
    }
}
