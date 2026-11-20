package br.com.almadocarro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Serviço em primeiro plano: mantém o Bolt vivo com a tela apagada ou com outro app na frente
 * (microfone, adaptador OBD e GPS continuam ativos). Aparece como notificação fixa.
 */
class AlmaService : Service() {

    companion object {
        private const val CHANNEL = "alma_bg"
        private const val ID = 7
        private const val ACTION_STOP = "br.com.almadocarro.STOP"

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bolt em segundo plano", NotificationManager.IMPORTANCE_LOW))
        }

        fun startIfAllowed(ctx: Context) {
            if (types(ctx) == 0) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, AlmaService::class.java))
            } catch (e: Throwable) {
                Log.w(Alma.TAG, "Serviço em primeiro plano não iniciou", e)
            }
        }

        private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

        fun types(ctx: Context): Int {
            if (Build.VERSION.SDK_INT < 30) return ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            var t = 0
            if (granted(ctx, Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT < 31 || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            return t
        }
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Alma.obd.disconnect()
            Alma.web.eval("try{__alma.voice.stopListening()}catch(e){}")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AlmaService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Bolt está com você")
            .setContentText("Ouvindo pelo nome e acompanhando o carro.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_bolt, "Encerrar", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        try {
            ServiceCompat.startForeground(this, ID, n, types(this))
        } catch (e: Throwable) {
            Log.w(Alma.TAG, "startForeground recusado", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alma:bolt")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Throwable) { }
        wake = null
        super.onDestroy()
    }
}
