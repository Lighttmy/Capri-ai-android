package com.asistenteia.movil

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Ventana emergente con solo la cara (WebView con rostro.html: glitch + boca que habla).
 * Todos los métodos deben llamarse desde el hilo principal. El WebView se prepara de antemano
 * para que al decir la frase de activación aparezca al instante.
 */
class RostroOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var web: WebView? = null
    private var enPantalla = false
    private var cargado = false
    private var pendiente = ArrayList<String>()
    var alTocar: (() -> Unit)? = null

    fun puede(): Boolean = Settings.canDrawOverlays(ctx)
    val visible: Boolean get() = enPantalla

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun preparar() {
        if (web != null) return
        val w = WebView(ctx.applicationContext)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        with(w.settings) {
            javaScriptEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        w.addJavascriptInterface(Puente(), "Android")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                cargado = true
                pendiente.forEach { js(it) }
                pendiente.clear()
            }
        }
        w.loadUrl("file:///android_asset/rostro.html")
        web = w
    }

    private fun js(codigo: String) {
        val w = web ?: return
        if (!cargado) { pendiente.add(codigo); return }
        w.evaluateJavascript(codigo, null)
    }

    private inner class Puente {
        @JavascriptInterface fun listo() {}
        @JavascriptInterface fun ocultado() { Nucleo.principal.post { quitar() } }
        @JavascriptInterface fun tocado() { Nucleo.principal.post { alTocar?.invoke() } }
    }

    private fun tam(): Pair<Int, Int> {
        val d = ctx.resources.displayMetrics
        val ancho = (minOf(d.widthPixels, d.heightPixels) * 0.78f).toInt()
        return ancho to (ancho * 520 / 560)
    }

    fun mostrar(): Boolean {
        if (!puede()) return false
        preparar()
        val w = web ?: return false
        if (!enPantalla) {
            val (an, al) = tam()
            val tipo = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val p = WindowManager.LayoutParams(
                an, al, tipo,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            )
            p.gravity = Gravity.CENTER
            try {
                (w.parent as? android.view.ViewGroup)?.removeView(w)
                wm.addView(w, p)
                enPantalla = true
            } catch (e: Exception) {
                return false
            }
        }
        js("Rostro.mostrar()")
        return true
    }

    fun estado(s: String) = js("Rostro.estado('$s')")
    fun silaba(f: Float) = js("Rostro.silaba($f)")

    /** Animación de salida; la ventana se quita cuando la página avisa de que terminó. */
    fun ocultar() {
        if (!enPantalla) return
        js("Rostro.ocultar()")
        // por si la página no avisa
        Nucleo.principal.postDelayed({ quitar() }, 900)
    }

    fun quitar() {
        val w = web ?: return
        if (!enPantalla) return
        enPantalla = false
        runCatching { wm.removeViewImmediate(w) }
    }

    fun destruir() {
        quitar()
        web?.destroy()
        web = null
        cargado = false
    }
}
