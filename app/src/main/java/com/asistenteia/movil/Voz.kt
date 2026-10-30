package com.asistenteia.movil

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.random.Random

/** Reconocimiento de voz continuo: se reinicia solo tras cada frase o error. Solo hilo principal. */
class Escucha(
    private val ctx: Context,
    private val alParcial: (String) -> Unit,
    private val alFinal: (String) -> Unit,
) {
    private var rec: SpeechRecognizer? = null
    private var activa = false
    private var errores = 0
    private val h = Nucleo.principal
    private val reinicio = Runnable { arrancar() }

    fun disponible() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun iniciar() {
        if (activa) return
        activa = true
        errores = 0
        arrancar()
    }

    fun detener() {
        activa = false
        h.removeCallbacks(reinicio)
        destruir()
    }

    /** Pausa (p. ej. mientras habla); `reanudar()` la vuelve a activar. */
    fun pausar() = detener()
    fun reanudar() = iniciar()

    private fun destruir() {
        rec?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        rec = null
    }

    private fun arrancar() {
        if (!activa) return
        destruir()
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() { errores = 0 }
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (t.isNotBlank()) alParcial(t)
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val seguir = activa
                if (t.isNotBlank()) alFinal(t)
                if (seguir && activa) programar(150)
            }
            override fun onError(e: Int) {
                if (!activa) return
                errores = if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 0 else errores + 1
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { activa = false; return }
                programar(minOf(200L + errores * 500L, 5000L))
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        runCatching { r.startListening(i) }.onFailure { programar(1000) }
    }

    private fun programar(ms: Long) {
        h.removeCallbacks(reinicio)
        h.postDelayed(reinicio, ms)
    }
}

/** Voz del asistente. Avisa de cada palabra/«sílaba» para animar la boca. Solo hilo principal. */
class Habla(ctx: Context, private val ajustes: Ajustes) {
    private var tts: TextToSpeech? = null
    private var listo = false
    private var cola: Triple<String, (Float) -> Unit, () -> Unit>? = null
    private var alSilaba: (Float) -> Unit = {}
    private var alTerminar: () -> Unit = {}
    private var hablando = false
    private val h = Nucleo.principal
    private val pulso = object : Runnable {
        override fun run() {
            if (!hablando) return
            alSilaba(0.25f + Random.nextFloat() * 0.75f)
            h.postDelayed(this, 95L + Random.nextLong(0, 70))
        }
    }

    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            val t = tts
            if (st != TextToSpeech.SUCCESS || t == null) return@TextToSpeech
            val r = t.setLanguage(Locale("es", "ES"))
            listo = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { h.post { hablando = true; h.removeCallbacks(pulso); h.post(pulso) } }
                override fun onDone(id: String?) { h.post { terminar() } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { h.post { terminar() } }
                override fun onError(id: String?, code: Int) { h.post { terminar() } }
                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { h.post { if (hablando) alSilaba(1f) } }
            })
            cola?.let { (txt, s, f) -> cola = null; decir(txt, s, f) }
        }
    }

    private fun terminar() {
        if (!hablando && cola == null && !pendiente) return
        hablando = false
        pendiente = false
        h.removeCallbacks(pulso)
        val f = alTerminar
        alTerminar = {}
        f()
    }

    private var pendiente = false

    fun decir(texto: String, silaba: (Float) -> Unit, terminado: () -> Unit) {
        val t = tts
        if (t == null || !listo) {
            if (t != null && !listo && cola == null) { // motor sin español o aún iniciando
                cola = Triple(texto, silaba, terminado)
                h.postDelayed({ val c = cola; if (c != null && !listo) { cola = null; c.third() } }, 4000)
            } else terminado()
            return
        }
        alSilaba = silaba
        alTerminar = terminado
        pendiente = true
        t.setPitch(ajustes.tonoVoz)
        t.setSpeechRate(ajustes.velocidadVoz)
        val max = TextToSpeech.getMaxSpeechInputLength() - 10
        val limpio = texto.take(max)
        val r = t.speak(limpio, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
        if (r != TextToSpeech.SUCCESS) terminar()
    }

    fun callar() {
        tts?.stop()
        terminar()
    }

    fun apagar() {
        hablando = false
        h.removeCallbacks(pulso)
        tts?.shutdown()
        tts = null
    }
}
