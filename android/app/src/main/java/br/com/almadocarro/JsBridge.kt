package br.com.almadocarro

import android.os.Build
import android.webkit.JavascriptInterface
import br.com.almadocarro.car.CarNotifier
import org.json.JSONObject
import java.util.concurrent.Executors

/** Métodos chamados pelo painel (alma-android.js) como `AlmaAndroid.xxx(...)`. */
class JsBridge {
    private val io = Executors.newSingleThreadExecutor()

    /* ---------------- voz: fala ---------------- */

    @JavascriptInterface
    fun ttsVoices(): String = Alma.tts.voicesJson()

    @JavascriptInterface
    fun ttsSpeak(id: String, text: String, lang: String, voiceName: String, rate: Float, pitch: Float, volume: Float) {
        Alma.main.post { Alma.tts.speak(id, text, lang, voiceName, rate, pitch, volume) }
    }

    @JavascriptInterface
    fun ttsCancel() {
        Alma.main.post { Alma.tts.cancel() }
    }

    /* ---------------- voz: escuta ---------------- */

    @JavascriptInterface
    fun sttStart(id: String, lang: String, partial: Boolean) {
        Alma.main.post { Alma.stt.start(id, lang, partial) }
    }

    @JavascriptInterface
    fun sttStop(id: String) {
        Alma.main.post { Alma.stt.stop(id) }
    }

    @JavascriptInterface
    fun sttAbort(id: String) {
        Alma.main.post { Alma.stt.abort(id) }
    }

    @JavascriptInterface
    fun wakeHeard() = Alma.onWakeHeard()

    @JavascriptInterface
    fun voiceInfo(): String = JSONObject()
        .put("version", BuildConfig.VERSION_NAME)
        .put("escuta", Alma.stt.engineName)
        .put("fala", Alma.tts.describe())
        .toString()

    /* ---------------- avisos para a tela do carro ---------------- */

    @JavascriptInterface
    fun assistantMessage(text: String) {
        if (text.isBlank()) return
        Alma.main.post {
            if (Alma.carConnected && !Alma.carVisible) CarNotifier.notify(Alma.app, "Bolt", text, false)
        }
    }

    @JavascriptInterface
    fun alertRaised(level: String, title: String, message: String) {
        Alma.main.post {
            if (Alma.carConnected && !Alma.carVisible) {
                CarNotifier.notify(Alma.app, title.ifBlank { "Alerta" }, message, level == "critical")
            }
        }
    }

    @JavascriptInterface
    fun webReady() {
        Alma.main.post { AlmaService.startIfAllowed(Alma.app) }
    }

    /* ---------------- OBD2 (contrato window.BoltNative do projeto) ---------------- */

    @JavascriptInterface
    fun capabilities(): String = JSONObject()
        .put("bluetoothClassic", true)
        .put("bluetoothLE", true)
        .put("wifi", true)
        .put("gps", true)
        .put("microphone", true)
        .put("platform", "android")
        .put("sdk", Build.VERSION.SDK_INT)
        .toString()

    @JavascriptInterface
    fun obdList(cb: String, mode: String) = run(cb) { Alma.obd.list(mode).toString() }

    @JavascriptInterface
    fun obdConnect(cb: String, mode: String, address: String) = run(cb) { Alma.obd.connect(mode, address); null }

    @JavascriptInterface
    fun obdWrite(cb: String, text: String) = run(cb) { Alma.obd.write(text); null }

    @JavascriptInterface
    fun obdDisconnect(cb: String) = run(cb) { Alma.obd.disconnect(); null }

    private fun run(cb: String, block: () -> String?) {
        io.execute {
            try {
                val out = block()
                resolve(cb, true, out)
            } catch (e: Throwable) {
                resolve(cb, false, JSONObject.quote(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun resolve(cb: String, ok: Boolean, json: String?) {
        val payload = if (json == null) "null" else JSONObject.quote(json)
        Alma.js("window.__almaNative&&__almaNative.cb(${JSONObject.quote(cb)},$ok,$payload)")
    }
}
