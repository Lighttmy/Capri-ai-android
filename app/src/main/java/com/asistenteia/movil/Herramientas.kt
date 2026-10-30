package com.asistenteia.movil

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Las acciones que el asistente puede hacer en el móvil. Cada una devuelve una frase con lo que pasó. */
class Herramientas(context: Context, private val ajustes: Ajustes) {
    private val ctx: Context = context.applicationContext
    private val audio: AudioManager = ctx.getSystemService(AudioManager::class.java)

    fun ejecutar(nombre: String, a: Map<String, Any?>): String {
        fun s(k: String) = (a[k] as? String)?.trim().orEmpty()
        fun n(k: String) = (a[k] as? Number)?.toInt()
        return when (nombre) {
            "volumen" -> volumen(s("accion"), n("nivel"), s("tipo"))
            "brillo" -> brillo(s("accion"), n("nivel"))
            "linterna" -> linterna(s("estado"))
            "abrir_aplicacion" -> abrirAplicacion(s("nombre"))
            "listar_aplicaciones" -> listarAplicaciones(s("filtro"))
            "llamar" -> llamar(s("destino"))
            "enviar_sms" -> enviarSms(s("destinatario"), s("texto"))
            "responder_mensaje" -> MensajesService.instancia?.responder(s("contacto"), s("texto"))
                ?: "Falta dar acceso a las notificaciones a la aplicación (en sus ajustes) para poder contestar mensajes."
            "leer_mensajes" -> MensajesService.leer(s("contacto"))
            "alarma" -> alarma(n("hora"), n("minutos") ?: 0, s("mensaje"))
            "temporizador" -> temporizador(n("segundos"), s("mensaje"))
            "control_multimedia" -> multimedia(s("accion"))
            "abrir_web" -> abrirWeb(s("url"))
            "buscar_en_web" -> if (s("consulta").isEmpty()) "Dime qué quieres buscar." else
                abrirWeb("https://www.google.com/search?q=" + URLEncoder.encode(s("consulta"), "UTF-8"))
            "ajustes" -> ajustesPanel(s("panel"))
            "accion_sistema" -> AccesibilidadService.instancia?.accion(s("accion"))
                ?: "Falta activar el servicio de accesibilidad «Asistente IA» en los ajustes del móvil."
            "fecha_hora" -> SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "ES")).format(Date())
            "recordar" -> recordar(s("clave"), s("valor"))
            else -> "No conozco la herramienta «$nombre»."
        }
    }

    private fun lanzar(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    // ------------------------------------------------------------------ volumen y brillo

    private fun volumen(accion: String, nivel: Int?, tipo: String): String {
        val (stream, nombre) = when (tipo) {
            "timbre" -> AudioManager.STREAM_RING to "del timbre"
            "alarma" -> AudioManager.STREAM_ALARM to "de la alarma"
            "llamada" -> AudioManager.STREAM_VOICE_CALL to "de las llamadas"
            else -> AudioManager.STREAM_MUSIC to "multimedia"
        }
        return try {
            val tope = audio.getStreamMaxVolume(stream)
            val actual = audio.getStreamVolume(stream)
            val paso = max(1, (tope * 0.15).roundToInt())
            val nuevo = when (accion) {
                "subir" -> min(tope, actual + paso)
                "bajar" -> max(0, actual - paso)
                "fijar" -> {
                    if (nivel == null) return "Dime a qué porcentaje quieres el volumen."
                    (max(0, min(100, nivel)) / 100.0 * tope).roundToInt()
                }
                "silenciar" -> {
                    audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    return "Volumen $nombre silenciado."
                }
                "activar_sonido" -> {
                    audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    return "Sonido $nombre activado."
                }
                else -> return "No entiendo esa acción de volumen."
            }
            audio.setStreamVolume(stream, nuevo, AudioManager.FLAG_SHOW_UI)
            "Volumen $nombre al ${(nuevo * 100.0 / tope).roundToInt()} %."
        } catch (e: SecurityException) {
            "Android no me deja cambiar ese volumen ahora (quizá está activo el modo No molestar)."
        }
    }

    private fun brillo(accion: String, nivel: Int?): String {
        if (!Settings.System.canWrite(ctx)) {
            lanzar(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            return "Falta el permiso «Modificar ajustes del sistema». Te he abierto la pantalla para activarlo."
        }
        val cr = ctx.contentResolver
        if (accion == "automatico") {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "Brillo automático activado."
        }
        val actual = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val nuevo = when (accion) {
            "subir" -> min(255, actual + 64)
            "bajar" -> max(1, actual - 64)
            "fijar" -> {
                if (nivel == null) return "Dime a qué porcentaje quieres el brillo."
                max(1, (max(0, min(100, nivel)) / 100.0 * 255).roundToInt())
            }
            else -> return "No entiendo esa acción de brillo."
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, nuevo)
        return "Brillo al ${(nuevo * 100.0 / 255).roundToInt()} %."
    }

    // ------------------------------------------------------------------ linterna

    private fun linterna(estado: String): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return "Este móvil no tiene linterna."
        val encender = when (estado) {
            "encender" -> true
            "apagar" -> false
            else -> !linternaEncendida
        }
        cm.setTorchMode(id, encender)
        linternaEncendida = encender
        return if (encender) "Linterna encendida." else "Linterna apagada."
    }

    // ------------------------------------------------------------------ aplicaciones

    private data class App(val nombre: String, val paquete: String)

    private fun apps(): List<App> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.paquete }.sortedBy { it.nombre.lowercase() }
    }

    private fun abrirAplicacion(nombre: String): String {
        if (nombre.isBlank()) return "Dime qué aplicación quieres abrir."
        val q = Activacion.normalizar(nombre)
        val lista = apps()
        val puntuadas = lista.map { app ->
            val n = Activacion.normalizar(app.nombre)
            val p = when {
                n == q -> 100
                n.startsWith(q) -> 80
                n.contains(q) -> 60
                q.contains(n) && n.length >= 4 -> 50
                Activacion.similitud(n, q) >= 0.8 -> 40
                else -> 0
            }
            app to p
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (puntuadas.isEmpty()) {
            if (q in setOf("ajustes", "configuracion")) { lanzar(Intent(Settings.ACTION_SETTINGS)); return "Ajustes abiertos." }
            return "No encuentro ninguna aplicación que se llame «$nombre»."
        }
        val mejor = puntuadas[0]
        val empatadas = puntuadas.filter { it.second == mejor.second }
        if (empatadas.size > 1 && mejor.second < 100) {
            return "Hay varias que encajan con «$nombre»: " + empatadas.take(5).joinToString(", ") { it.first.nombre } + ". Dime cuál."
        }
        val i = ctx.packageManager.getLaunchIntentForPackage(mejor.first.paquete) ?: return "No puedo abrir ${mejor.first.nombre}."
        lanzar(i)
        return "${mejor.first.nombre} abierta."
    }

    private fun listarAplicaciones(filtro: String): String {
        val f = Activacion.normalizar(filtro)
        val l = apps().filter { f.isEmpty() || Activacion.normalizar(it.nombre).contains(f) }
        if (l.isEmpty()) return "No hay aplicaciones que coincidan."
        return "Aplicaciones: " + l.take(40).joinToString(", ") { it.nombre } + if (l.size > 40) " y ${l.size - 40} más." else "."
    }

    // ------------------------------------------------------------------ contactos, llamadas y SMS

    private fun tienePermiso(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Devuelve (nombre, número) del contacto, o un mensaje de error. */
    private fun resolver(destino: String): Pair<String, String>? {
        val soloNumero = destino.filter { it.isDigit() || it == '+' }
        if (soloNumero.count { it.isDigit() } >= 5 && destino.count { it.isLetter() } == 0) return destino to soloNumero
        if (!tienePermiso(Manifest.permission.READ_CONTACTS)) return null
        val q = Activacion.normalizar(destino)
        val encontrados = LinkedHashMap<String, String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val nom = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val nn = Activacion.normalizar(nom)
                if (nn == q || nn.startsWith(q) || nn.contains(q) || Activacion.similitud(nn, q) >= 0.8) encontrados.putIfAbsent(nom, num)
            }
        }
        return when {
            encontrados.isEmpty() -> "" to ""
            encontrados.size == 1 || encontrados.keys.any { Activacion.normalizar(it) == q } -> {
                val e = encontrados.entries.firstOrNull { Activacion.normalizar(it.key) == q } ?: encontrados.entries.first()
                e.key to e.value
            }
            else -> "VARIOS" to encontrados.keys.take(4).joinToString(", ")
        }
    }

    private fun resolverODecir(destino: String): Pair<Pair<String, String>?, String?> {
        val r = resolver(destino)
        return when {
            r == null -> null to "Falta el permiso de contactos para buscar a «$destino». Actívalo en la aplicación o dime el número."
            r.first == "" -> null to "No encuentro a «$destino» en tus contactos."
            r.first == "VARIOS" -> null to "Hay varios contactos que encajan: ${r.second}. Dime cuál."
            else -> r to null
        }
    }

    private fun llamar(destino: String): String {
        if (destino.isBlank()) return "Dime a quién quieres llamar."
        val (r, error) = resolverODecir(destino)
        if (r == null) return error!!
        lanzar(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(r.second))))
        return "Te dejo preparada la llamada a ${r.first}; solo tienes que pulsar llamar."
    }

    private fun enviarSms(destinatario: String, texto: String): String {
        if (destinatario.isBlank() || texto.isBlank()) return "Necesito a quién y qué mensaje enviar."
        if (!tienePermiso(Manifest.permission.SEND_SMS)) return "Falta el permiso para enviar SMS. Actívalo en la aplicación."
        val (r, error) = resolverODecir(destinatario)
        if (r == null) return error!!
        val sms = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val partes = sms.divideMessage(texto.take(480))
        sms.sendMultipartTextMessage(r.second, null, partes, null, null)
        return "SMS enviado a ${r.first}."
    }

    // ------------------------------------------------------------------ alarmas, música, web, ajustes

    private fun alarma(hora: Int?, minutos: Int, mensaje: String): String {
        if (hora == null || hora !in 0..23 || minutos !in 0..59) return "Dime una hora válida para la alarma."
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hora).putExtra(AlarmClock.EXTRA_MINUTES, minutos)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (mensaje.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, mensaje)
        lanzar(i)
        return "Alarma puesta a las %02d:%02d.".format(hora, minutos)
    }

    private fun temporizador(segundos: Int?, mensaje: String): String {
        if (segundos == null || segundos !in 1..86_400) return "Dime cuánto tiempo quieres en el temporizador."
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, segundos).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (mensaje.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, mensaje)
        lanzar(i)
        return if (segundos % 60 == 0) "Temporizador de ${segundos / 60} minutos puesto." else "Temporizador de $segundos segundos puesto."
    }

    private fun multimedia(accion: String): String {
        val tecla = when (accion) {
            "pausar" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "reproducir" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "siguiente" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "anterior" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "No entiendo esa acción de música."
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, tecla))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, tecla))
        return "Hecho: $accion."
    }

    private fun abrirWeb(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return "Dime qué página quieres abrir."
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        val uri = Uri.parse(u)
        if (uri.host.isNullOrBlank()) return "Esa dirección no es válida."
        lanzar(Intent(Intent.ACTION_VIEW, uri))
        return "Abierta ${uri.host}."
    }

    private fun ajustesPanel(panel: String): String {
        val accion = when (panel) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "volumen", "sonido" -> Settings.ACTION_SOUND_SETTINGS
            "brillo" -> Settings.ACTION_DISPLAY_SETTINGS
            "bateria" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "aplicaciones" -> Settings.ACTION_APPLICATION_SETTINGS
            "accesibilidad" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notificaciones", "general" -> Settings.ACTION_SETTINGS
            else -> return "No conozco esa pantalla de ajustes."
        }
        lanzar(Intent(accion))
        return "Ajustes de $panel abiertos. Android no me deja cambiarlos sin que lo toques tú."
    }

    private fun recordar(clave: String, valor: String): String {
        val v = valor.trim().take(60)
        if (v.isEmpty()) return "No me has dicho qué recordar."
        when (clave) {
            "nombre" -> ajustes.nombre = v
            "apodo" -> ajustes.apodo = v
            "gusto" -> ajustes.gustos = (ajustes.gustos.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.equals(v, true) } + v).takeLast(8).joinToString(", ")
            else -> return "No sé guardar eso."
        }
        return "Anotado: $clave = $v."
    }

    companion object {
        @Volatile var linternaEncendida = false
    }
}
