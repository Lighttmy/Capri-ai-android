package com.asistenteia.movil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager

/**
 * Servicio en primer plano: escucha la frase de activación; al oírla abre la cara, atiende la orden,
 * la ejecuta con el cerebro y responde hablando.
 */
class AsistenteService : Service() {
    private enum class Estado { ESPERA, ORDEN, PENSANDO, HABLANDO }

    private val h = Nucleo.principal
    private lateinit var ajustes: Ajustes
    private lateinit var rostro: RostroOverlay
    private lateinit var escucha: Escucha
    private lateinit var habla: Habla
    private var estado = Estado.ESPERA
    private var wake: PowerManager.WakeLock? = null
    private var silenciado = false
    private var turno = 0
    private val finOrden = Runnable { if (estado == Estado.ORDEN) cerrar() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ajustes = Nucleo.ajustes(this)
        rostro = RostroOverlay(this)
        rostro.alTocar = { if (estado == Estado.HABLANDO) { habla.callar() } else if (estado == Estado.ESPERA) activar("") }
        habla = Habla(this, ajustes)
        escucha = Escucha(this, ::alParcial, ::alFinal)
        MensajesService.crearCanales(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Asistente escuchando", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pararPi = PendingIntent.getService(this, 1, Intent(this, AsistenteService::class.java).setAction(ACCION_PARAR), PendingIntent.FLAG_IMMUTABLE)
        val abrirPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CANAL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Asistente activo").setContentText("Di «${ajustes.frase}»")
            .setContentIntent(abrirPi).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Parar", pararPi).build()).build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID_NOTIF, n)
        } catch (e: Exception) { // sin permiso de micrófono o inicio no permitido
            ajustes.activo = false
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACCION_PARAR -> { ajustes.activo = false; stopSelf(); return START_NOT_STICKY }
            ACCION_ROSTRO -> { rostro.mostrar(); rostro.estado("hablando"); h.postDelayed({ if (estado == Estado.ESPERA) rostro.ocultar() }, 3500); return START_STICKY }
            ACCION_ORDEN -> {
                val t = intent.getStringExtra("texto").orEmpty()
                if (t.isNotBlank()) { activar(t); return START_STICKY }
            }
        }
        ajustes.activo = true
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asistente:escucha").apply { setReferenceCounted(false); acquire() }
        }
        silenciarPitidos(true)
        rostro.preparar()
        if (estado == Estado.ESPERA) escucha.iniciar()
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        escucha.detener()
        habla.apagar()
        rostro.destruir()
        silenciarPitidos(false)
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    // ---- reconocimiento

    private fun alParcial(t: String) {
        if (estado != Estado.ESPERA) return
        if (Activacion.buscar(t, ajustes.frase) != null) {
            // la cara aparece en cuanto se reconoce la frase, sin esperar a que acabes de hablar
            if (!rostro.visible && rostro.mostrar()) rostro.estado("escuchando")
        } else if (rostro.visible) {
            // falsa alarma (se oyó parecido pero no era): la quitamos si el final tampoco lo confirma
        }
    }

    private fun alFinal(t: String) {
        when (estado) {
            Estado.ESPERA -> {
                val resto = Activacion.buscar(t, ajustes.frase)
                if (resto == null) { if (rostro.visible) rostro.ocultar(); return }
                activar(resto)
            }
            Estado.ORDEN -> {
                h.removeCallbacks(finOrden)
                if (t.isNotBlank()) ejecutar(t) else cerrar()
            }
            else -> {}
        }
    }

    /** `resto` = lo que ya dijiste detrás de la frase («oye asistente sube el volumen» → «sube el volumen»). */
    private fun activar(resto: String) {
        val limpio = resto.trim()
        if (!rostro.visible) rostro.mostrar()
        if (limpio.length >= 3) { ejecutar(limpio); return }
        estado = Estado.ORDEN
        rostro.estado("escuchando")
        h.removeCallbacks(finOrden)
        h.postDelayed(finOrden, 8000)
        escucha.iniciar()
    }

    private fun ejecutar(orden: String) {
        val mio = ++turno
        estado = Estado.PENSANDO
        escucha.pausar()
        rostro.estado("pensando")
        Nucleo.hilos.execute {
            val r = runCatching { Nucleo.cerebro(this).conversar(orden) }.getOrElse { Resultado("Algo ha fallado: ${it.message ?: "error desconocido"}.", ok = false) }
            h.post { if (mio == turno && estado == Estado.PENSANDO) responder(r.texto) }
        }
    }

    private fun responder(texto: String) {
        estado = Estado.HABLANDO
        rostro.estado("hablando")
        val mio = turno
        habla.decir(texto.ifBlank { "Hecho." }, { rostro.silaba(it) }) { if (mio == turno) cerrar() }
    }

    private fun cerrar() {
        turno++
        estado = Estado.ESPERA
        h.removeCallbacks(finOrden)
        rostro.estado("reposo")
        rostro.ocultar()
        escucha.iniciar()
    }

    private fun silenciarPitidos(si: Boolean) {
        if (!ajustes.silenciarPitidos || si == silenciado) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flag = if (si) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, flag, 0)
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, flag, 0)
            silenciado = si
        }
    }

    companion object {
        const val CANAL = "escucha"
        const val ID_NOTIF = 1
        const val ACCION_PARAR = "com.asistenteia.movil.PARAR"
        const val ACCION_ROSTRO = "com.asistenteia.movil.ROSTRO"
        const val ACCION_ORDEN = "com.asistenteia.movil.ORDEN"

        fun iniciar(ctx: Context, accion: String? = null, texto: String? = null) {
            val i = Intent(ctx, AsistenteService::class.java)
            if (accion != null) i.action = accion
            if (texto != null) i.putExtra("texto", texto)
            ctx.startForegroundService(i)
        }

        fun parar(ctx: Context) {
            Nucleo.ajustes(ctx).activo = false
            ctx.stopService(Intent(ctx, AsistenteService::class.java))
        }
    }
}
