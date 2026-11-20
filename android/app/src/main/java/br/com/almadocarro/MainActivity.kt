package br.com.almadocarro

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private var pendingFiles: ValueCallback<Array<Uri>>? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = pendingFiles
        pendingFiles = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        AlmaService.startIfAllowed(this)
        Alma.web.eval("window.__almaNative&&__almaNative.permissionsChanged&&__almaNative.permissionsChanged()")
        offerExtras()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF070B11.toInt())
        val note = TextView(this).apply {
            text = "O painel está aberto na tela da multimídia (Android Auto)."
            setTextColor(0xFFB8C7D9.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        Alma.web.fileChooser = { cb, params ->
            pendingFiles?.onReceiveValue(null)
            pendingFiles = cb
            try {
                pickFiles.launch(params.createIntent())
                true
            } catch (_: Throwable) {
                pendingFiles = null
                false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = Alma.web.webView
                if (wv.parent === container && wv.canGoBack()) wv.goBack() else moveTaskToBack(true)
            }
        })

        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        Alma.activity = this
        Alma.activityVisible = true
        Alma.web.attachActivity(container)
    }

    override fun onStop() {
        Alma.activityVisible = false
        Alma.web.attachActivity(null)
        if (Alma.activity === this) Alma.activity = null
        super.onStop()
    }

    private fun requestNeededPermissions() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            want += Manifest.permission.BLUETOOTH_CONNECT
            want += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        val missing = want.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            AlmaService.startIfAllowed(this)
            offerExtras()
        } else {
            askPermissions.launch(missing.toTypedArray())
        }
    }

    /** Uma vez só: sem economia de bateria matando o Bolt e (opcional) permissão de aparecer na frente. */
    @SuppressLint("BatteryLife")
    private fun offerExtras() {
        val prefs = getSharedPreferences("alma_app", MODE_PRIVATE)
        val pm = getSystemService(PowerManager::class.java)
        if (!prefs.getBoolean("asked_battery", false) && !pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("asked_battery", true).apply()
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
            }
            return
        }
        if (!prefs.getBoolean("asked_overlay", false) && !Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("asked_overlay", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Bolt na frente quando chamado")
                .setMessage("Para o Bolt aparecer na tela do celular quando você disser \"Bolt\" com outro app aberto, permita \"Sobrepor a outros apps\" na próxima tela. No Android Auto isso não é necessário.")
                .setPositiveButton("Permitir") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (_: Throwable) {
                    }
                }
                .setNegativeButton("Agora não", null)
                .show()
        }
    }
}
