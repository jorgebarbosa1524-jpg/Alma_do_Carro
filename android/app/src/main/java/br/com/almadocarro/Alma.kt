package br.com.almadocarro

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.car.app.connection.CarConnection
import br.com.almadocarro.car.CarNotifier
import java.lang.ref.WeakReference

/**
 * Estado único do processo. O painel web (WebView) vive aqui, fora da Activity,
 * para continuar rodando quando o celular está em segundo plano ou a tela do carro mostra outro app.
 */
object Alma {
    const val TAG = "Alma"

    lateinit var app: Application
        private set
    val main = Handler(Looper.getMainLooper())

    val focus by lazy { FocusManager(app) }
    val tts by lazy { NativeTts(app) }
    val stt by lazy { NativeStt(app) }
    val obd by lazy { ObdBridge(app) }
    val web by lazy { WebHost(app) }

    @Volatile var activityVisible = false
    @Volatile var carVisible = false
    @Volatile var carConnected = false
    private var activityRef: WeakReference<Activity>? = null

    var activity: Activity?
        get() = activityRef?.get()
        set(value) { activityRef = if (value == null) null else WeakReference(value) }

    fun init(application: Application) {
        app = application
        try {
            CarConnection(application).type.observeForever { type ->
                carConnected = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
                    type == CarConnection.CONNECTION_TYPE_NATIVE
            }
        } catch (e: Throwable) {
            Log.w(TAG, "CarConnection indisponível", e)
        }
    }

    /** Executa JavaScript no painel (sempre na thread principal). */
    fun js(code: String) {
        main.post { web.eval(code) }
    }

    /** Alguém disse "Bolt": dá prioridade de áudio e, se possível, traz o Bolt para a frente. */
    fun onWakeHeard() {
        main.post {
            focus.acquire(FocusManager.LISTEN, 12_000)
            if (carConnected && !carVisible) {
                CarNotifier.notify(app, "Bolt", "Pode falar, estou ouvindo.", false)
            } else if (!carConnected && !activityVisible && Settings.canDrawOverlays(app)) {
                bringToFront(app)
            }
        }
    }

    fun bringToFront(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Não foi possível trazer para a frente", e)
        }
    }
}

class AlmaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Alma.init(this)
        CarNotifier.createChannel(this)
        AlmaService.createChannel(this)
    }
}
