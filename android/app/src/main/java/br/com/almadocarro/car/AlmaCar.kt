package br.com.almadocarro.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.car.app.validation.HostValidator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import br.com.almadocarro.Alma
import br.com.almadocarro.AlmaService
import br.com.almadocarro.R

/**
 * Entrada do Android Auto. Categoria NAVEGAÇÃO: é a única que deixa o app desenhar a própria tela
 * na multimídia — e é nela que o painel da Alma do Carro é exibido, igual ao celular.
 */
class AlmaCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }

    override fun onCreateSession(): Session = AlmaSession()
}

class AlmaSession : Session() {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                AlmaService.startIfAllowed(carContext)
            }

            override fun onStart(owner: LifecycleOwner) {
                Alma.carVisible = true
                CarNotifier.clear(carContext)
            }

            override fun onStop(owner: LifecycleOwner) {
                Alma.carVisible = false
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = DashboardScreen(carContext)
}

class DashboardScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                try { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) } catch (_: Throwable) { }
                Alma.web.setCarSurface(null, 0, 0, 0)
            }
        })
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_mic))
                    .setTitle("Bolt")
                    .setOnClickListener { Alma.onWakeHeard(); Alma.web.eval("window.__almaNative&&__almaNative.command('talk')") }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_eyes))
                    .setOnClickListener { Alma.web.eval("window.__almaNative&&__almaNative.command('standby')") }
                    .build()
            )
            .build()
        val b = NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setBackgroundColor(CarColor.createCustom(0xFF070B11.toInt(), 0xFF070B11.toInt()))
        if (carContext.carAppApiLevel >= 2) {
            b.setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            b.setPanModeListener { }
        }
        return b.build()
    }

    /* ---------- a tela da multimídia vira a tela do painel ---------- */

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Alma.web.setCarSurface(surfaceContainer.surface, surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Alma.web.setCarSurface(null, 0, 0, 0)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Alma.web.setCarStableArea(stableArea)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}

    private var lastX = 0f
    private var lastY = 0f

    override fun onClick(x: Float, y: Float) {
        lastX = x
        lastY = y
        Alma.web.carClick(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        Alma.web.carScroll(lastX, lastY, distanceX, distanceY)
    }
}

/** Avisos do Bolt que aparecem por cima na multimídia quando outro app (ex.: Spotify) está na tela. */
object CarNotifier {
    private const val CHANNEL = "alma_car"
    private const val ID = 42

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bolt na multimídia", NotificationManager.IMPORTANCE_HIGH))
    }

    fun notify(ctx: Context, title: String, text: String, critical: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = CarPendingIntent.getCarApp(
            ctx, 1,
            Intent(Intent.ACTION_VIEW).setComponent(ComponentName(ctx, AlmaCarAppService::class.java)),
            0
        )
        val body = text.take(240)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (critical) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(R.drawable.ic_bolt)
                    .setContentIntent(open)
                    .addAction(R.drawable.ic_bolt, "Abrir", open)
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .build()
            )
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID, n)
        } catch (_: SecurityException) {
        }
    }

    fun clear(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID)
    }
}
