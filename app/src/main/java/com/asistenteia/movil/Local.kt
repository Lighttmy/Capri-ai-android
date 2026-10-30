package com.asistenteia.movil

/**
 * Intérprete de órdenes SIN IA y sin internet: reconoce las órdenes habituales en español
 * («sube el volumen», «alarma a las siete y media», «llama a mamá»…) y las convierte en llamadas a herramientas.
 * Es el modo por defecto, no necesita clave ni conexión. Código puro Kotlin (sin Android) para poder probarlo.
 */
object Local {
    private const val DE = "áéíóúüñÁÉÍÓÚÜÑ"
    private const val A = "aeiouunAEIOUUN"

    /** Minúsculas y sin acentos, manteniendo exactamente la misma longitud (para poder recortar el texto original). */
    fun plegar(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val i = DE.indexOf(c)
            sb.append(if (i >= 0) A[i].lowercaseChar() else c.lowercaseChar())
        }
        return sb.toString()
    }

    private val NUM = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7,
        "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15,
        "veinte" to 20, "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80,
        "noventa" to 90, "cien" to 100, "ciento" to 100, "medio" to 50, "mitad" to 50,
    )
    private const val N = "(\\d{1,3}|un|uno|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|catorce|quince|veinte|treinta|cuarenta|cincuenta|sesenta|setenta|ochenta|noventa|cien|ciento|medio|mitad)"

    private fun num(s: String?): Int? = s?.let { it.toIntOrNull() ?: NUM[it] }

    private fun re(p: String) = Regex(p)
    private fun Regex.m(t: String) = find(t)

    private val VOL = re("\\bvolumen\\b|\\bsonido\\b")
    private val BRI = re("\\bbrillo\\b")

    /** Devuelve (herramienta, argumentos) o null si no reconoce nada. */
    fun interpretar(original: String): Pair<String, Map<String, Any?>>? {
        val o = original.trim().trim('.', ',', '!', '?', '¿', '¡')
        val t = plegar(o).replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        fun sub(m: MatchResult, g: Int): String = o.substring(m.groups[g]!!.range.first, m.groups[g]!!.range.last + 1).trim().trim('.', ',', '"', '«', '»')

        // --- contestar / mandar mensaje (antes que nada: el texto puede contener otras palabras clave)
        re("^(?:contesta|responde|respondele|contestale)(?: le| a| al| a la)? (.+?) (?:diciendo(?: que)?|que dice|que|:) (.+)$").m(t)?.let {
            return "responder_mensaje" to mapOf("contacto" to sub(it, 1), "texto" to sub(it, 2))
        }
        re("^(?:contesta|responde|respondele|contestale)(?: el mensaje| al mensaje| el ultimo mensaje)?(?: diciendo(?: que)?| con| :)? (.+)$").m(t)?.let {
            return "responder_mensaje" to mapOf("texto" to sub(it, 1))
        }
        re("^(?:manda|envia|mandale|enviale|escribe|escribele)(?: me)?(?: un| una)?(?: sms| mensaje| whatsapp| texto)?(?: de texto)? a (.+?) (?:diciendo(?: que)?|que dice|que|:) (.+)$").m(t)?.let {
            return "enviar_sms" to mapOf("destinatario" to sub(it, 1), "texto" to sub(it, 2))
        }

        // --- leer mensajes
        if (re("\\b(lee|leeme|leer|dime)\\b.*\\bmensajes\\b|que me han (escrito|mandado)|tengo (algun )?(mensaje|mensajes)|mensajes nuevos").containsMatchIn(t))
            return "leer_mensajes" to mapOf("contacto" to (re("mensajes? de (.+)$").m(t)?.let { sub(it, 1) } ?: ""))

        // --- hora y fecha
        if (re("que hora es|dime la hora|que dia es|a que (dia|fecha) estamos|que fecha es").containsMatchIn(t)) return "fecha_hora" to emptyMap()

        // --- alarma
        if (re("\\balarma\\b|despiertame|levantame").containsMatchIn(t) && !VOL.containsMatchIn(t)) {
            val (h, mi) = hora(t) ?: return null
            return "alarma" to mapOf("hora" to h, "minutos" to mi)
        }
        // --- temporizador
        if (re("temporizador|cuenta atras|avisame (en|dentro de)|cronometro de").containsMatchIn(t)) {
            val seg = duracion(t) ?: return null
            return "temporizador" to mapOf("segundos" to seg)
        }

        // --- linterna
        if (re("\\blinterna\\b|\\bflash\\b").containsMatchIn(t)) {
            val e = when {
                re("apaga|quita|desactiva|cierra").containsMatchIn(t) -> "apagar"
                re("enciende|prende|activa|pon|abre").containsMatchIn(t) -> "encender"
                else -> "alternar"
            }
            return "linterna" to mapOf("estado" to e)
        }

        // --- volumen / brillo
        if (BRI.containsMatchIn(t)) {
            if (re("automatico").containsMatchIn(t)) return "brillo" to mapOf("accion" to "automatico")
            val n = re("(?:al|a|en)\\s+$N\\s*(?:%|por ?ciento)?").m(t)?.let { num(it.groupValues[1]) }
            if (n != null && !re("\\b(sube|subir|aumenta|baja|bajar|reduce)\\b").containsMatchIn(t)) return "brillo" to mapOf("accion" to "fijar", "nivel" to n.coerceIn(0, 100))
            if (re("maximo|al maximo|a tope").containsMatchIn(t)) return "brillo" to mapOf("accion" to "fijar", "nivel" to 100)
            if (re("minimo|al minimo").containsMatchIn(t)) return "brillo" to mapOf("accion" to "fijar", "nivel" to 5)
            return when {
                re("\\b(sube|subir|aumenta|aumentar|mas|mayor|alto)\\b").containsMatchIn(t) -> "brillo" to mapOf("accion" to "subir")
                re("\\b(baja|bajar|reduce|reducir|menos|menor|bajo|oscuro)\\b").containsMatchIn(t) -> "brillo" to mapOf("accion" to "bajar")
                else -> null
            }
        }
        if (VOL.containsMatchIn(t) && !re("ajustes|configuracion|abre").containsMatchIn(t)) {
            val tipo = when {
                re("timbre|tono|llamadas?").containsMatchIn(t) -> "timbre"
                re("alarma").containsMatchIn(t) -> "alarma"
                else -> "multimedia"
            }
            if (re("silencia|silencio|mute|quita el sonido|sin sonido|mutea").containsMatchIn(t)) return "volumen" to mapOf("accion" to "silenciar", "tipo" to tipo)
            if (re("activa el sonido|quita el silencio|pon el sonido|desmutea").containsMatchIn(t)) return "volumen" to mapOf("accion" to "activar_sonido", "tipo" to tipo)
            val n = re("(?:al|a|en)\\s+$N\\s*(?:%|por ?ciento)?").m(t)?.let { num(it.groupValues[1]) }
            if (n != null && !re("\\b(sube|subir|aumenta|baja|bajar|reduce)\\b").containsMatchIn(t)) return "volumen" to mapOf("accion" to "fijar", "nivel" to n.coerceIn(0, 100), "tipo" to tipo)
            if (re("maximo|al maximo|a tope").containsMatchIn(t)) return "volumen" to mapOf("accion" to "fijar", "nivel" to 100, "tipo" to tipo)
            if (re("minimo|al minimo").containsMatchIn(t)) return "volumen" to mapOf("accion" to "fijar", "nivel" to 0, "tipo" to tipo)
            return when {
                re("\\b(sube|subir|aumenta|aumentar|mas|mayor|alto)\\b").containsMatchIn(t) -> "volumen" to mapOf("accion" to "subir", "tipo" to tipo)
                re("\\b(baja|bajar|reduce|reducir|menos|menor|bajo)\\b").containsMatchIn(t) -> "volumen" to mapOf("accion" to "bajar", "tipo" to tipo)
                else -> null
            }
        }

        // --- música
        if (re("\\b(musica|cancion|canciones|reproduccion|video|podcast)\\b|siguiente|anterior|\\b(pausa|pausar|reanuda|continua)\\b").containsMatchIn(t) &&
            !re("\\b(busca|abre|llama)\\b").containsMatchIn(t)) {
            val a = when {
                re("siguiente|salta|pasa la|proxima").containsMatchIn(t) -> "siguiente"
                re("anterior|atras la|vuelve a la").containsMatchIn(t) -> "anterior"
                re("pausa|para |detén|deten|detiene|silencia la musica|quita la musica|apaga la musica").containsMatchIn(t + " ") -> "pausar"
                re("pon|reproduce|reanuda|continua|sigue|dale|play").containsMatchIn(t) -> "reproducir"
                else -> null
            }
            if (a != null) return "control_multimedia" to mapOf("accion" to a)
        }

        // --- sistema (accesibilidad)
        val sis = when {
            re("^(ve |vuelve |volver |ir )?(a|al)? ?(la )?(pantalla de )?inicio$|^(ve |vuelve |volver )(a|al) (inicio|escritorio|menu principal)|^(pantalla de )?inicio$|^home$").containsMatchIn(t) -> "inicio"
            re("^(vuelve|volver|ve|ir) atras$|^atras$|^retrocede$").containsMatchIn(t) -> "atras"
            re("apps? recientes|aplicaciones recientes|multitarea").containsMatchIn(t) -> "recientes"
            re("(abre|muestra|baja|despliega) (el panel de |las )?notificaciones|panel de notificaciones").containsMatchIn(t) -> "notificaciones"
            re("ajustes rapidos|panel rapido").containsMatchIn(t) -> "ajustes_rapidos"
            re("bloquea|bloquear|apaga la pantalla").containsMatchIn(t) && re("movil|pantalla|telefono|bloquea$|bloquea ").containsMatchIn(t) -> "bloquear"
            re("captura|pantallazo|screenshot").containsMatchIn(t) -> "captura"
            else -> null
        }
        if (sis != null) return "accion_sistema" to mapOf("accion" to sis)

        // --- ajustes del móvil
        re("(?:abre|ve a|muestra|pon|activa|desactiva|enciende|apaga)(?: los| las| el| la)?(?: ajustes| configuracion| panel| opciones)?(?: de)? ?(?:el |la )?(wifi|wi-fi|bluetooth|bateria|sonido|notificaciones|accesibilidad)\\b").m(t)?.let {
            val p = it.groupValues[1].replace("wi-fi", "wifi")
            return "ajustes" to mapOf("panel" to p)
        }

        // --- llamar
        re("^(?:llama|llamame|telefonea|marca)(?: por telefono)?(?: a| al)? (.+)$").m(t)?.let { return "llamar" to mapOf("destino" to sub(it, 1)) }

        // --- web y búsqueda
        re("^(?:busca|buscame|googlea|investiga)(?: en google| en internet| en la web)?(?: sobre| acerca de)? (.+)$").m(t)?.let { return "buscar_en_web" to mapOf("consulta" to sub(it, 1)) }
        re("^(?:abre|ve a|entra en|entra a)(?: la pagina| la web| el sitio| el enlace)?(?: de)? ((?:https?://)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:/\\S*)?)$").m(t)?.let { return "abrir_web" to mapOf("url" to sub(it, 1)) }

        // --- abrir aplicación
        re("^(?:abre|abreme|lanza|inicia|arranca|ejecuta|pon|ve a|entra en|entra a)(?: la aplicacion de| la aplicacion| la app de| la app| el programa| el| la)? (.+)$").m(t)?.let { return "abrir_aplicacion" to mapOf("nombre" to sub(it, 1)) }

        return null
    }

    /** Hora de una alarma: «a las 7», «a las siete y media», «a las 8:15 de la tarde», «a las ocho menos cuarto». */
    fun hora(t: String): Pair<Int, Int>? {
        val m = re("\\b(?:a la|a las|para las|para la|sobre las)\\s+$N(?:\\s*(?::|\\.|h)\\s*(\\d{2}))?(?:\\s+(y media|y cuarto|menos cuarto|menos veinte|menos diez|menos cinco|en punto|y (?:$N)))?(?:\\s+(?:de|por) la\\s+(manana|tarde|noche|madrugada))?").find(t) ?: return null
        var h = num(m.groupValues[1]) ?: return null
        var mi = m.groupValues[2].toIntOrNull() ?: 0
        val extra = m.groupValues[3]
        when {
            extra == "y media" -> mi = 30
            extra == "y cuarto" -> mi = 15
            extra == "menos cuarto" -> { h -= 1; mi = 45 }
            extra == "menos veinte" -> { h -= 1; mi = 40 }
            extra == "menos diez" -> { h -= 1; mi = 50 }
            extra == "menos cinco" -> { h -= 1; mi = 55 }
            extra.startsWith("y ") -> mi = num(extra.removePrefix("y ").trim()) ?: 0
        }
        val franja = m.groupValues.last()
        if ((franja == "tarde" || franja == "noche") && h < 12) h += 12
        if (franja == "noche" && h == 24) h = 0
        if (franja == "manana" && h == 12) h = 0
        if (h < 0) h += 24
        if (h > 23 || mi > 59) return null
        return h to mi
    }

    /** Duración en segundos: «5 minutos», «media hora», «una hora y media», «30 segundos». */
    fun duracion(t: String): Int? {
        if (re("media hora").containsMatchIn(t)) return 1800
        if (re("cuarto de hora").containsMatchIn(t)) return 900
        var total = 0
        var hay = false
        for (m in re("$N\\s*(segundos?|segs?|minutos?|mins?|horas?)\\b").findAll(t)) {
            val n = num(m.groupValues[1]) ?: continue
            val u = m.groupValues[2]
            total += n * when { u.startsWith("seg") -> 1; u.startsWith("min") -> 60; else -> 3600 }
            hay = true
        }
        if (!hay) return null
        if (re("hora y media").containsMatchIn(t)) total += 1800
        else if (re("minuto y medio").containsMatchIn(t)) total += 30
        return if (total in 1..86400) total else null
    }

    /** Charla mínima sin IA. */
    fun charla(original: String): String? {
        val t = plegar(original).trim().trim('.', ',', '!', '?', '¿', '¡')
        return when {
            re("^(hola|buenas|hey|ey)( que tal)?$").containsMatchIn(t) -> "¡Hola! Dime qué necesitas."
            re("^(gracias|muchas gracias|vale gracias)$").containsMatchIn(t) -> "¡De nada!"
            re("como estas|que tal estas|que tal estas").containsMatchIn(t) -> "Muy bien, listo para ayudarte."
            re("quien eres|como te llamas").containsMatchIn(t) -> "Soy tu asistente. Puedo manejar el volumen, el brillo, abrir apps, poner alarmas y contestar mensajes."
            re("que puedes hacer|ayuda|que sabes hacer").containsMatchIn(t) ->
                "Puedo subir o bajar el volumen y el brillo, abrir aplicaciones, llamar, poner alarmas y temporizadores, controlar la música, leer y contestar mensajes."
            re("^(adios|hasta luego|nada|cancela|vale|ok|déjalo|dejalo)$").containsMatchIn(t) -> "Vale."
            else -> null
        }
    }

    const val NO_ENTENDIDO = "No te he entendido. Prueba con cosas como «sube el volumen», «alarma a las siete y media», «abre WhatsApp» o «llama a mamá»."
}
