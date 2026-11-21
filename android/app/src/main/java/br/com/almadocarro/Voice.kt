package br.com.almadocarro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private fun q(s: String?) = JSONObject.quote(s ?: "")

/* =====================================================================================
 * Foco de áudio: é o que faz o Bolt "passar na frente" do Spotify.
 *  - LISTEN (depois de ouvir "Bolt"): pede foco exclusivo curto → o Spotify pausa enquanto você fala.
 *  - SPEAK (Bolt falando): pede foco com "abaixar o volume" → a música abaixa e volta sozinha.
 * ===================================================================================== */
class FocusManager(ctx: Context) {
    companion object {
        const val LISTEN = "listen"
        const val SPEAK = "speak"
    }

    private val am = ctx.getSystemService(AudioManager::class.java)
    private val holds = HashMap<String, Long>() // motivo -> expira em (uptime ms)
    private val gen = HashMap<String, Int>()
    private var current: AudioFocusRequest? = null
    private var currentGain = 0
    private val listener = AudioManager.OnAudioFocusChangeListener { }

    fun acquire(reason: String, ms: Long = 0) {
        holds[reason] = if (ms > 0) SystemClock.uptimeMillis() + ms else Long.MAX_VALUE
        gen[reason] = (gen[reason] ?: 0) + 1
        update()
        if (ms > 0) Alma.main.postDelayed({ update() }, ms + 50)
    }

    fun release(reason: String, delayMs: Long = 900) {
        val g = gen[reason] ?: return
        Alma.main.postDelayed({
            if (gen[reason] == g) {
                holds.remove(reason)
                update()
            }
        }, delayMs)
    }

    /** Há conversa em andamento (Bolt ouvindo depois do nome ou falando). */
    val active: Boolean get() = holds.isNotEmpty()

    fun releaseNow(reason: String) {
        holds.remove(reason)
        gen[reason] = (gen[reason] ?: 0) + 1
        update()
    }

    private fun update() {
        val now = SystemClock.uptimeMillis()
        holds.entries.removeAll { it.value <= now }
        val want = when {
            holds.isEmpty() -> 0
            holds.containsKey(LISTEN) -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            else -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        if (want == currentGain) return
        current?.let { am.abandonAudioFocusRequest(it) }
        current = null
        currentGain = 0
        if (want == 0) return
        val usage = if (want == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) AudioAttributes.USAGE_ASSISTANT
        else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        val req = AudioFocusRequest.Builder(want)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener, Alma.main)
            .build()
        val r = am.requestAudioFocus(req)
        if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            current = req
            currentGain = want
        }
    }
}

/* =====================================================================================
 * Fala nativa (TextToSpeech) — substitui o speechSynthesis do navegador.
 * Toca como "orientação de navegação": no Android Auto ela sai por cima da música.
 * ===================================================================================== */
class NativeTts(private val ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx, this)
    private var ready = false
    private val pending = ArrayList<() -> Unit>()
    private var voicesCache = "[]"
    private var currentId: String? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            Log.w(Alma.TAG, "TextToSpeech indisponível ($status)")
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        try { tts.setLanguage(Locale("pt", "BR")) } catch (_: Throwable) { }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = event(id, "start", null)
            override fun onDone(id: String) {
                event(id, "end", null)
                finished(id)
            }

            @Deprecated("API antiga")
            override fun onError(id: String) {
                event(id, "error", "synthesis-failed")
                finished(id)
            }

            override fun onError(id: String, errorCode: Int) {
                event(id, "error", if (errorCode == TextToSpeech.ERROR_NETWORK || errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT) "network" else "synthesis-failed")
                finished(id)
            }

            override fun onStop(id: String, interrupted: Boolean) {
                event(id, "error", "interrupted")
                finished(id)
            }
        })
        voicesCache = buildVoices()
        Alma.js("window.__almaNative&&__almaNative.voices(${q(voicesCache)})")
        val list = ArrayList(pending)
        pending.clear()
        list.forEach { it() }
    }

    private fun finished(id: String) {
        Alma.main.post {
            if (currentId == id) {
                currentId = null
                Alma.focus.release(FocusManager.SPEAK, 700)
                Alma.focus.release(FocusManager.LISTEN, 700)
            }
        }
    }

    private fun event(id: String, type: String, err: String?) {
        Alma.js("window.__almaNative&&__almaNative.tts(${q(id)},${q(type)},${q(err)})")
    }

    private fun buildVoices(): String {
        val arr = JSONArray()
        try {
            val def = tts.defaultVoice?.name
            val all = tts.voices?.filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }.orEmpty()
            all.sortedBy { it.locale.toLanguageTag() }.forEach { v ->
                arr.put(
                    JSONObject()
                        .put("name", v.name)
                        .put("lang", v.locale.toLanguageTag())
                        .put("localService", !v.isNetworkConnectionRequired)
                        .put("default", v.name == def)
                )
            }
        } catch (e: Throwable) {
            Log.w(Alma.TAG, "Não foi possível listar vozes", e)
        }
        return arr.toString()
    }

    fun voicesJson(): String = voicesCache

    /** O Bolt está falando agora (nada pode ser silenciado). */
    val speakingNow: Boolean get() = currentId != null

    private fun pickVoice(name: String, lang: String): Voice? {
        return try {
            val all = tts.voices.orEmpty()
            all.firstOrNull { it.name == name }
                ?: all.filter { it.locale.language == "pt" && !it.isNetworkConnectionRequired && it.locale.country.equals("BR", true) }
                    .minByOrNull { it.quality * -1 }
                ?: all.firstOrNull { it.locale.toLanguageTag().equals(lang, true) }
        } catch (_: Throwable) {
            null
        }
    }

    fun speak(id: String, text: String, lang: String, voiceName: String, rate: Float, pitch: Float, volume: Float) {
        if (!ready) {
            pending.add { speak(id, text, lang, voiceName, rate, pitch, volume) }
            return
        }
        Alma.beep.unmuteNow()
        Alma.beep.ensureMediaAudible()
        pickVoice(voiceName, lang)?.let { try { tts.setVoice(it) } catch (_: Throwable) { } }
        tts.setSpeechRate(rate.coerceIn(0.1f, 3f))
        tts.setPitch(pitch.coerceIn(0.1f, 2f))
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
        Alma.focus.acquire(FocusManager.SPEAK)
        currentId = id
        val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (r != TextToSpeech.SUCCESS) {
            event(id, "error", "synthesis-failed")
            finished(id)
        }
    }

    fun cancel() {
        tts.stop()
        Alma.focus.release(FocusManager.SPEAK, 300)
    }
}

/* =====================================================================================
 * Escuta nativa (SpeechRecognizer) — substitui o webkitSpeechRecognition do navegador.
 * Roda dentro do serviço em primeiro plano, então funciona com o celular em segundo plano.
 * ===================================================================================== */
class NativeStt(private val ctx: Context) {
    private var rec: SpeechRecognizer? = null
    // Reconhecedor do Google (online): entende melhor o nome "Bolt" e responde mais rápido.
    // O reconhecedor dentro do aparelho fica desligado (entendia pior em alguns celulares).
    private var onDevice = false
    private var activeId: String? = null
    private var activeLang = "pt-BR"
    private var activePartial = true
    private var activeContinuous = false
    private var askedDownload = false
    private var retriedFor: String? = null

    private fun safeOnDeviceAvailable(): Boolean = try {
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    } catch (_: Throwable) {
        false
    }

    private fun recognizer(): SpeechRecognizer {
        rec?.let { return it }
        val r = if (onDevice && Build.VERSION.SDK_INT >= 33) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        return r
    }

    private fun destroy() {
        try { rec?.destroy() } catch (_: Throwable) { }
        rec = null
    }

    private fun ev(id: String, type: String, a: String? = null, final: Boolean = false) {
        Alma.js("window.__almaNative&&__almaNative.stt(${q(id)},${q(type)},${q(a)},$final)")
    }

    fun start(id: String, lang: String, partial: Boolean, continuous: Boolean = false) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ev(id, "error", "not-allowed"); ev(id, "end"); return
        }
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            ev(id, "error", "service-not-allowed"); ev(id, "end"); return
        }
        activeId?.let { old ->
            try { rec?.cancel() } catch (_: Throwable) { }
            ev(old, "end")
        }
        activeId = id
        activeLang = lang.ifBlank { "pt-BR" }
        activePartial = partial
        activeContinuous = continuous
        begin(id)
    }

    private fun begin(id: String) {
        val r = try { recognizer() } catch (e: Throwable) {
            Log.w(Alma.TAG, "SpeechRecognizer falhou", e)
            ev(id, "error", "service-not-allowed"); ev(id, "end"); activeId = null; return
        }
        r.setRecognitionListener(Listener(id))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, activePartial)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Pausa curta para dar a frase por terminada: resposta mais rápida.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        // O som de "comecei a ouvir" do Android toca agora: silencia só esse instante.
        Alma.beep.muteFor(1500)
        try {
            r.startListening(intent)
        } catch (e: Throwable) {
            Log.w(Alma.TAG, "startListening falhou", e)
            destroy()
            ev(id, "error", "aborted"); ev(id, "end"); activeId = null
        }
    }

    fun stop(id: String) {
        if (activeId != id) return
        try { rec?.stopListening() } catch (_: Throwable) { }
    }

    fun abort(id: String) {
        if (activeId != id) { ev(id, "end"); return }
        try { rec?.cancel() } catch (_: Throwable) { }
        activeId = null
        ev(id, "end")
    }

    private inner class Listener(private val id: String) : RecognitionListener {
        private fun mine() = activeId == id

        override fun onReadyForSpeech(params: Bundle?) {
            Alma.beep.muteFor(700)
            if (mine()) ev(id, "start")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { Alma.beep.muteFor(900) }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(b: Bundle?) {
            if (!mine()) return
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isNotEmpty()) ev(id, "result", t, false)
        }

        override fun onResults(b: Bundle?) {
            Alma.beep.muteFor(500)
            if (!mine()) return
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isNotEmpty()) ev(id, "result", t, true) else ev(id, "error", "no-speech")
            activeId = null
            ev(id, "end")
        }

        override fun onError(error: Int) {
            Alma.beep.muteFor(500)
            if (!mine()) return
            // O reconhecimento no aparelho não tem o pacote pt-BR? Cai para o reconhecedor padrão uma vez.
            if (onDevice && retriedFor != id && error in setOf(12, 13, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER, 14)) {
                retriedFor = id
                onDevice = false
                destroy()
                Alma.main.postDelayed({ if (activeId == id) begin(id) }, 120)
                return
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT || error == 11) destroy()
            val code = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, 11 -> "network"
                SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                else -> "aborted"
            }
            activeId = null
            ev(id, "error", code)
            ev(id, "end")
        }
    }
}

/* =====================================================================================
 * Silencia o "bip" que o reconhecimento de voz do Android toca a cada início de escuta.
 * Só os sons de sistema/notificação, por menos de 2 segundos. O volume de MÍDIA nunca é mexido
 * (é por ele que o Bolt fala e o Spotify toca). Nada é silenciado durante uma conversa.
 * ===================================================================================== */
class BeepMuter(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val prefs = ctx.getSharedPreferences("alma_beep", Context.MODE_PRIVATE)
    private val muted = ArrayList<Int>()
    private var until = 0L
    private val check = Runnable { tick() }

    init {
        // Recupera de um fechamento inesperado com algo silenciado.
        prefs.getString("muted", "")!!.split(",").mapNotNull { it.toIntOrNull() }.forEach {
            try { am.adjustStreamVolume(it, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Throwable) { }
        }
        prefs.edit().remove("muted").commit()
        // A versão 1.1.0/1.1.1 podia deixar a mídia no mudo se o app fechasse no meio: devolve o som.
        ensureMediaAudible()
    }

    fun ensureMediaAudible() {
        try {
            if (am.isStreamMute(AudioManager.STREAM_MUSIC)) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Throwable) {
        }
    }

    fun muteFor(ms: Long) {
        if (!prefs.getBoolean("enabled", true)) return
        if (Alma.tts.speakingNow || Alma.focus.active) return
        val end = SystemClock.uptimeMillis() + ms
        if (end > until) until = end
        if (muted.isEmpty()) {
            val streams = arrayListOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)
            for (st in streams) {
                try {
                    if (!am.isStreamMute(st)) {
                        am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                        muted += st
                    }
                } catch (_: Throwable) {
                }
            }
            prefs.edit().putString("muted", muted.joinToString(",")).commit()
        }
        Alma.main.removeCallbacks(check)
        Alma.main.postDelayed(check, (until - SystemClock.uptimeMillis()).coerceAtLeast(50))
    }

    private fun tick() {
        val left = until - SystemClock.uptimeMillis()
        if (left > 20) Alma.main.postDelayed(check, left) else unmuteNow()
    }

    fun unmuteNow() {
        Alma.main.removeCallbacks(check)
        until = 0
        if (muted.isEmpty()) return
        for (st in muted) {
            try { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Throwable) { }
        }
        muted.clear()
        prefs.edit().remove("muted").commit()
    }
}
