package com.asistenteia.movil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.atomic.AtomicInteger

/** Un mensaje recibido (leído de su notificación) y, si la notificación lo permite, cómo contestarlo. */
class MensajeRecibido(
    val clave: String,
    val paquete: String,
    val app: String,
    val remitente: String,
    val texto: String,
    val esGrupo: Boolean,
    val accion: Notification.Action?,
    val tiempo: Long,
)

class Borrador(val id: Int, val mensaje: MensajeRecibido, val texto: String)

/**
 * Lee las notificaciones de mensajes y contesta por ti usando el botón «Responder» de la propia
 * notificación (así funciona con WhatsApp, Telegram, SMS…, sin tocar sus datos).
 * Por seguridad: por defecto nada se contesta; con «borrador» tú pulsas Enviar; los mensajes con dinero,
 * claves o urgencias nunca se contestan solos; y hay un límite de respuestas por conversación.
 */
class MensajesService : NotificationListenerService() {
    private val ajustes by lazy { Nucleo.ajustes(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instancia = this
        crearCanales(this)
    }

    override fun onListenerDisconnected() {
        if (instancia === this) instancia = null
        super.onListenerDisconnected()
        if (Build.VERSION.SDK_INT >= 24) runCatching { requestRebind(ComponentName(this, MensajesService::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        runCatching { procesar(sbn) }
    }

    private fun procesar(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category != Notification.CATEGORY_MESSAGE && !APPS_MENSAJERIA.containsKey(sbn.packageName)) return
        val extras = n.extras ?: return
        val titulo = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var texto = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        var remitente = titulo
        val esGrupo = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null
        @Suppress("DEPRECATION")
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val ultimo = msgs.last() as? Bundle
            if (ultimo != null) {
                ultimo.getCharSequence("text")?.toString()?.let { texto = it }
                val quien = ultimo.getCharSequence("sender")?.toString()
                if (!quien.isNullOrBlank() && esGrupo) remitente = quien
            }
        }
        if (texto.isBlank() || RESUMEN.containsMatchIn(texto)) return // «3 mensajes nuevos»: no es un mensaje

        val accion = buscarAccionResponder(n)
        val app = APPS_MENSAJERIA[sbn.packageName] ?: sbn.packageName
        val m = MensajeRecibido(sbn.key, sbn.packageName, app, remitente, texto, esGrupo, accion, System.currentTimeMillis())
        guardar(m)

        val id = sbn.key + "|" + texto.hashCode()
        if (limitador.yaVisto(id)) return
        val entrante = Entrante(app, remitente, texto, esGrupo, sbn.key)
        val decision = Politica.decidir(ajustes.modoDe(sbn.packageName), entrante, ajustes.permitirGrupos, ajustes.nombre)
        limitador.marcarVisto(id, m.tiempo)
        if (decision == Decision.IGNORAR || accion == null) return
        Nucleo.hilos.execute {
            runCatching { gestionar(m, decision, id) }
        }
    }

    private fun buscarAccionResponder(n: Notification): Notification.Action? {
        fun candidatas(l: List<Notification.Action>?) = l.orEmpty().filter { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        var c = candidatas(n.actions?.toList())
        if (c.isEmpty()) c = candidatas(Notification.WearableExtender(n).actions)
        if (c.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= 28) c.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }?.let { return it }
        return c.first()
    }

    private fun gestionar(m: MensajeRecibido, decision: Decision, id: String) {
        val respuesta = redactar(m) ?: return
        if (decision == Decision.AUTO) {
            if (!limitador.permite(m.clave, id, System.currentTimeMillis())) return
            if (enviarPorAccion(m, respuesta)) {
                limitador.registrar(m.clave, id, System.currentTimeMillis())
                avisar("Respondí a ${m.remitente}", "«${m.texto.take(80)}» → «${respuesta.take(120)}»")
            }
        } else {
            val b = Borrador(siguienteId.incrementAndGet(), m, respuesta)
            borradores[b.id] = b
            publicarBorrador(b)
        }
    }

    /** Pide a la IA la respuesta, en nombre del usuario. null = no contestar. */
    private fun redactar(m: MensajeRecibido): String? {
        val yo = ajustes.nombre.ifBlank { "el usuario" }
        val sistema = "Eres $yo y contestas, en su nombre, un mensaje que ha recibido por ${m.app}. " +
            "Estilo: ${ajustes.estiloRespuesta} Escribe solo el texto del mensaje (máximo dos frases), sin comillas ni explicaciones, " +
            "en el idioma del mensaje. No inventes datos, planes ni compromisos que no conozcas; no prometas dinero ni des datos personales, " +
            "claves ni direcciones. Si el mensaje exige una decisión que solo $yo puede tomar o algo que no sabes, " +
            "contesta algo breve como que lo mira y le contesta luego."
        val usuario = "Mensaje de ${m.remitente}: «${m.texto}»"
        val bruto = Nucleo.cerebro(this).generar(sistema, usuario)
        val limpio = Politica.limpiarRespuesta(bruto, 300, if (ajustes.avisarIA) "(Respuesta automática) " else "")
        return limpio.ifBlank { null }
    }

    private fun enviarPorAccion(m: MensajeRecibido, texto: String): Boolean {
        val accion = m.accion ?: return false
        val ris = accion.remoteInputs ?: return false
        val ri = ris.firstOrNull { it.allowFreeFormInput } ?: return false
        val intent = Intent()
        val datos = Bundle()
        datos.putCharSequence(ri.resultKey, texto)
        RemoteInput.addResultsToIntent(ris, intent, datos)
        return try {
            accion.actionIntent.send(this, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    /** Contestar por voz («contesta a Ana que sí»). Devuelve la frase que se dice al usuario. */
    fun responder(contacto: String, texto: String): String {
        if (texto.isBlank()) return "No me has dicho qué contestar."
        val q = Activacion.normalizar(contacto)
        val m = synchronized(recientes) {
            recientes.lastOrNull { it.accion != null && (q.isEmpty() || Activacion.normalizar(it.remitente).contains(q) || Activacion.similitud(Activacion.normalizar(it.remitente), q) >= 0.8) }
        } ?: return if (q.isEmpty()) "No tengo ningún mensaje reciente al que contestar." else "No tengo ningún mensaje reciente de $contacto al que contestar."
        val limpio = Politica.limpiarRespuesta(texto, 500)
        if (limpio.isEmpty()) return "No me has dicho qué contestar."
        return if (enviarPorAccion(m, limpio)) "Le he contestado a ${m.remitente} por ${m.app}." else
            "No he podido contestar a ${m.remitente}: la notificación ya no está disponible. Ábrela en ${m.app}."
    }

    private fun avisar(titulo: String, texto: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val nt = Notification.Builder(this, CANAL_INFO).setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(titulo).setContentText(texto).setStyle(Notification.BigTextStyle().bigText(texto)).setAutoCancel(true).build()
        nm.notify(siguienteId.incrementAndGet(), nt)
    }

    private fun publicarBorrador(b: Borrador) {
        val nm = getSystemService(NotificationManager::class.java)
        fun pi(accion: String, cod: Int) = PendingIntent.getBroadcast(
            this, b.id * 2 + cod, Intent(this, BorradorReceiver::class.java).setAction(accion).putExtra("id", b.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detalle = "${b.mensaje.remitente} (${b.mensaje.app}): «${b.mensaje.texto.take(100)}»\nRespuesta propuesta: «${b.texto}»"
        @Suppress("DEPRECATION")
        val nt = Notification.Builder(this, CANAL_BORRADORES).setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Borrador para ${b.mensaje.remitente}").setContentText(b.texto)
            .setStyle(Notification.BigTextStyle().bigText(detalle))
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_send, "Enviar", pi(BorradorReceiver.ENVIAR, 0)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Descartar", pi(BorradorReceiver.DESCARTAR, 1)).build())
            .build()
        nm.notify(NOTIF_BORRADOR + b.id, nt)
    }

    fun resolverBorrador(id: Int, enviar: Boolean) {
        val b = borradores.remove(id) ?: return
        getSystemService(NotificationManager::class.java).cancel(NOTIF_BORRADOR + id)
        if (!enviar) return
        Nucleo.hilos.execute {
            val ok = enviarPorAccion(b.mensaje, b.texto)
            Nucleo.principal.post {
                avisar(if (ok) "Enviado a ${b.mensaje.remitente}" else "No pude enviar a ${b.mensaje.remitente}",
                    if (ok) b.texto else "La conversación ya no está disponible en las notificaciones; ábrela en ${b.mensaje.app}.")
            }
        }
    }

    companion object {
        @Volatile var instancia: MensajesService? = null
        const val CANAL_BORRADORES = "borradores"
        const val CANAL_INFO = "avisos"
        private const val NOTIF_BORRADOR = 5000
        private val RESUMEN = Regex("^\\d+\\s+(mensajes?|messages?|chats?)\\b", RegexOption.IGNORE_CASE)
        private val siguienteId = AtomicInteger(100)
        private val recientes = ArrayList<MensajeRecibido>()
        private val borradores = HashMap<Int, Borrador>()
        private val limitador = LimitadorRespuestas(3, 60 * 60 * 1000L)

        private fun guardar(m: MensajeRecibido) {
            synchronized(recientes) {
                recientes.removeAll { it.clave == m.clave || m.tiempo - it.tiempo > 6 * 3600_000L }
                recientes.add(m)
                while (recientes.size > 30) recientes.removeAt(0)
            }
        }

        /** Lo que se lee al usuario cuando pregunta «¿qué me han escrito?». Los códigos de verificación se omiten. */
        fun leer(contacto: String): String {
            val q = Activacion.normalizar(contacto)
            val l = synchronized(recientes) {
                recientes.filter { !Politica.esCodigo(it.texto) && (q.isEmpty() || Activacion.normalizar(it.remitente).contains(q)) }.takeLast(5).reversed()
            }
            if (l.isEmpty()) return if (q.isEmpty()) "No tienes mensajes nuevos en las notificaciones." else "No hay mensajes recientes de $contacto."
            return l.joinToString(" ") { "De ${it.remitente} por ${it.app}: ${it.texto.take(160)}." }
        }

        fun crearCanales(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CANAL_BORRADORES, "Borradores de respuesta", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CANAL_INFO, "Avisos del asistente", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}

/** Recibe los botones «Enviar» y «Descartar» de los borradores. */
class BorradorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (id < 0) return
        MensajesService.instancia?.resolverBorrador(id, intent.action == ENVIAR)
    }

    companion object {
        const val ENVIAR = "com.asistenteia.movil.ENVIAR"
        const val DESCARTAR = "com.asistenteia.movil.DESCARTAR"
    }
}
