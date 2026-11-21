package br.com.almadocarro

import android.annotation.SuppressLint
import android.app.Application
import android.app.Presentation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Dono do WebView do painel. O mesmo WebView (sem recarregar) é colocado em um de três lugares:
 *  1. tela do carro (Android Auto) — quando o app está aberto na multimídia;
 *  2. tela do celular — quando a Activity está visível;
 *  3. uma tela virtual invisível — no resto do tempo, para o painel, o OBD e a voz seguirem
 *     funcionando a todo vapor em segundo plano (o Android desacelera páginas "escondidas").
 */
@SuppressLint("SetJavaScriptEnabled")
class WebHost(private val app: Application) {

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val START_URL = "https://$HOST/assets/www/index.html?nosw"
        private const val INJECT = "<script src=\"/assets/native/profile.js\"></script><script src=\"/assets/native/alma-android.js\"></script>"
    }

    private val ctx = android.content.MutableContextWrapper(app)
    val webView: WebView
    private val loader = WebViewAssetLoader.Builder()
        .setDomain(HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app))
        .build()

    private var activityContainer: FrameLayout? = null
    private var carTarget: DisplayTarget? = null
    private var hiddenTarget: DisplayTarget? = null
    private var carSurface: Surface? = null
    private var carStable: Rect? = null

    /** A Activity registra aqui como abrir o seletor de arquivos (fotos do veículo). */
    var fileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null

    init {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView = WebView(ctx)
        webView.setBackgroundColor(Color.rgb(7, 11, 17))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            textZoom = 100
        }
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        webView.addJavascriptInterface(JsBridge(), "AlmaAndroid")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host == HOST && (url.path == "/assets/www/index.html" || url.path == "/assets/www/")) {
                    return injectedIndex()
                }
                return loader.shouldInterceptRequest(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST) return false
                try {
                    app.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                val handled = fileChooser?.invoke(filePathCallback, fileChooserParams) ?: false
                if (!handled) filePathCallback.onReceiveValue(null)
                return true
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("AlmaWeb", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        route()
        webView.loadUrl(START_URL)
    }

    private fun injectedIndex(): WebResourceResponse? {
        return try {
            val html = app.assets.open("www/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val i = html.indexOf("<head>")
            val out = if (i >= 0) html.substring(0, i + 6) + "\n" + INJECT + html.substring(i + 6) else INJECT + html
            WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.e(Alma.TAG, "index.html não encontrado nos assets", e)
            null
        }
    }

    fun eval(code: String) {
        try {
            webView.evaluateJavascript(code, null)
        } catch (e: Throwable) {
            Log.w(Alma.TAG, "evaluateJavascript falhou", e)
        }
    }

    /* ------------------------------------------------------------------ destinos */

    fun attachActivity(container: FrameLayout?) {
        activityContainer = container
        route()
    }

    fun setCarSurface(surface: Surface?, width: Int, height: Int, dpi: Int) {
        if (surface == null || width <= 0 || height <= 0) {
            carSurface = null
            val old = carTarget
            carTarget = null
            route()
            old?.release()
            return
        }
        if (carTarget != null && carSurface === surface) {
            carTarget?.resize(width, height, dpi)
            return
        }
        val old = carTarget
        carTarget = null
        old?.let { route(); it.release() }
        carSurface = surface
        try {
            carTarget = DisplayTarget("car", surface, width, height, dpi, null)
            carStable?.let { applyCarInsets(it) }
        } catch (e: Throwable) {
            Log.e(Alma.TAG, "Não foi possível abrir o painel na tela do carro", e)
            carTarget = null
            drawCarFallback(surface, width, height)
        }
        route()
    }

    fun setCarStableArea(rect: Rect?) {
        carStable = rect
        if (rect != null) applyCarInsets(rect)
    }

    private fun applyCarInsets(r: Rect) {
        val t = carTarget ?: return
        val w = t.width
        val h = t.height
        if (r.width() <= 0 || r.height() <= 0) return
        t.root.setPadding(r.left.coerceIn(0, w), r.top.coerceIn(0, h), (w - r.right).coerceIn(0, w), (h - r.bottom).coerceIn(0, h))
    }

    /** Toque na tela do carro → toque no painel. */
    fun carClick(x: Float, y: Float) {
        val t = carTarget ?: return
        val down = SystemClock.uptimeMillis()
        val ev1 = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        ev1.source = InputDevice.SOURCE_TOUCHSCREEN
        t.root.dispatchTouchEvent(ev1)
        ev1.recycle()
        Alma.main.postDelayed({
            val ev2 = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            ev2.source = InputDevice.SOURCE_TOUCHSCREEN
            t.root.dispatchTouchEvent(ev2)
            ev2.recycle()
        }, 60)
    }

    /** Arrastar na tela do carro → rolar a parte do painel que está embaixo do dedo. */
    fun carScroll(x: Float, y: Float, dx: Float, dy: Float) {
        val t = carTarget ?: return
        val d = t.root.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val cx = (x - t.root.paddingLeft) / d
        val cy = (y - t.root.paddingTop) / d
        eval("window.__almaNative&&__almaNative.scroll($cx,$cy,${dx / d},${dy / d})")
    }

    private fun route() {
        val target: ViewGroup? = carTarget?.root ?: activityContainer ?: try {
            ensureHidden().root
        } catch (e: Throwable) {
            Log.w(Alma.TAG, "Tela virtual indisponível; o painel segue rodando sem ela", e)
            null
        }
        if (target == null) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            return
        }
        if (webView.parent !== target) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            ctx.setBaseContext(target.context)
            target.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            webView.onResume()
            webView.resumeTimers()
            webView.requestFocus()
        }
        val h = hiddenTarget
        if (h != null && target !== h.root) {
            hiddenTarget = null
            h.release()
        }
    }

    private fun ensureHidden(): DisplayTarget {
        hiddenTarget?.let { return it }
        val w = 1280
        val hgt = 720
        val reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            try { r.acquireLatestImage()?.close() } catch (_: Throwable) { }
        }, Alma.main)
        val t = DisplayTarget("hidden", reader.surface, w, hgt, 160, reader)
        hiddenTarget = t
        return t
    }

    private fun drawCarFallback(surface: Surface, w: Int, h: Int) {
        try {
            val c = surface.lockCanvas(null)
            c.drawColor(Color.rgb(7, 11, 17))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = h / 14f
                textAlign = Paint.Align.CENTER
            }
            c.drawText("Alma do Carro", w / 2f, h / 2f - p.textSize, p)
            p.textSize = h / 22f
            c.drawText("Abra o app no celular uma vez e volte aqui.", w / 2f, h / 2f + p.textSize, p)
            surface.unlockCanvasAndPost(c)
        } catch (_: Throwable) {
        }
    }

    /** Uma tela virtual (do carro ou invisível) com o painel dentro. */
    private inner class DisplayTarget(
        name: String,
        surface: Surface,
        var width: Int,
        var height: Int,
        dpi: Int,
        private val reader: ImageReader?
    ) {
        private val display: VirtualDisplay
        private val presentation: Presentation
        val root: FrameLayout

        init {
            val dm = app.getSystemService(DisplayManager::class.java)
            display = dm.createVirtualDisplay("alma-$name", width, height, dpi.coerceAtLeast(80), surface, 0)
            presentation = Presentation(app, display.display)
            root = FrameLayout(presentation.context)
            root.setBackgroundColor(Color.rgb(7, 11, 17))
            presentation.setContentView(root)
            presentation.show()
        }

        fun resize(w: Int, h: Int, dpi: Int) {
            width = w
            height = h
            display.resize(w, h, dpi.coerceAtLeast(80))
        }

        fun release() {
            try { root.removeAllViews() } catch (_: Throwable) { }
            try { presentation.dismiss() } catch (_: Throwable) { }
            try { display.release() } catch (_: Throwable) { }
            try { reader?.close() } catch (_: Throwable) { }
        }
    }
}
