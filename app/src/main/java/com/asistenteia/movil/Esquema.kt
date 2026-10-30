package com.asistenteia.movil

/** Descripción de las herramientas (acciones del móvil) que puede usar la IA. Sin dependencias de Android. */
data class Param(
    val nombre: String,
    val tipo: String = "string",           // string | integer | boolean
    val desc: String,
    val requerido: Boolean = false,
    val valores: List<String> = emptyList(),
)

data class Herramienta(val nombre: String, val desc: String, val params: List<Param> = emptyList())

object Esquema {
    val herramientas: List<Herramienta> = listOf(
        Herramienta(
            "volumen",
            "Sube, baja, silencia o fija el volumen del móvil. Por defecto actúa sobre el volumen multimedia (música, vídeos).",
            listOf(
                Param("accion", desc = "Qué hacer", requerido = true, valores = listOf("subir", "bajar", "silenciar", "activar_sonido", "fijar")),
                Param("nivel", "integer", "Porcentaje 0-100 (solo con «fijar»)"),
                Param("tipo", desc = "Qué volumen", valores = listOf("multimedia", "timbre", "alarma", "llamada")),
            ),
        ),
        Herramienta(
            "brillo",
            "Cambia el brillo de la pantalla: subir, bajar, fijarlo en un porcentaje o activar el brillo automático.",
            listOf(
                Param("accion", desc = "Qué hacer", requerido = true, valores = listOf("subir", "bajar", "fijar", "automatico")),
                Param("nivel", "integer", "Porcentaje 0-100 (solo con «fijar»)"),
            ),
        ),
        Herramienta(
            "linterna", "Enciende, apaga o alterna la linterna.",
            listOf(Param("estado", desc = "Qué hacer", requerido = true, valores = listOf("encender", "apagar", "alternar"))),
        ),
        Herramienta(
            "abrir_aplicacion", "Abre una aplicación instalada en el móvil por su nombre (WhatsApp, Spotify, Cámara, Ajustes…).",
            listOf(Param("nombre", desc = "Nombre de la aplicación", requerido = true)),
        ),
        Herramienta(
            "listar_aplicaciones", "Lista las aplicaciones instaladas (opcionalmente filtradas por un trozo del nombre).",
            listOf(Param("filtro", desc = "Parte del nombre")),
        ),
        Herramienta(
            "llamar", "Prepara una llamada a un contacto o número en el marcador (el usuario solo tiene que pulsar llamar).",
            listOf(Param("destino", desc = "Nombre del contacto o número de teléfono", requerido = true)),
        ),
        Herramienta(
            "enviar_sms", "Envía un SMS a un contacto o número. Úsalo solo cuando el usuario te pida enviar ese mensaje.",
            listOf(
                Param("destinatario", desc = "Nombre del contacto o número", requerido = true),
                Param("texto", desc = "Texto del mensaje", requerido = true),
            ),
        ),
        Herramienta(
            "responder_mensaje",
            "Contesta a un mensaje recibido (WhatsApp, Telegram, SMS…) desde su notificación. Si no se indica contacto, contesta a la última conversación.",
            listOf(
                Param("texto", desc = "Lo que se responde", requerido = true),
                Param("contacto", desc = "Nombre de quien escribió (opcional)"),
            ),
        ),
        Herramienta(
            "leer_mensajes", "Lee los últimos mensajes recibidos que siguen en las notificaciones (de quién y qué dicen).",
            listOf(Param("contacto", desc = "Solo los de esta persona (opcional)")),
        ),
        Herramienta(
            "alarma", "Programa una alarma a una hora (formato 24 h).",
            listOf(
                Param("hora", "integer", "Hora 0-23", requerido = true),
                Param("minutos", "integer", "Minutos 0-59"),
                Param("mensaje", desc = "Etiqueta de la alarma"),
            ),
        ),
        Herramienta(
            "temporizador", "Pone un temporizador de cuenta atrás.",
            listOf(
                Param("segundos", "integer", "Duración en segundos", requerido = true),
                Param("mensaje", desc = "Etiqueta"),
            ),
        ),
        Herramienta(
            "control_multimedia", "Controla la música o el vídeo que suena: pausar, reproducir, siguiente, anterior.",
            listOf(Param("accion", desc = "Qué hacer", requerido = true, valores = listOf("pausar", "reproducir", "siguiente", "anterior"))),
        ),
        Herramienta("abrir_web", "Abre una página web en el navegador.", listOf(Param("url", desc = "Dirección", requerido = true))),
        Herramienta("buscar_en_web", "Busca algo en Google.", listOf(Param("consulta", desc = "Qué buscar", requerido = true))),
        Herramienta(
            "ajustes", "Abre una pantalla de ajustes del móvil (Android no deja activar el wifi o el bluetooth directamente: se abre su panel).",
            listOf(
                Param(
                    "panel", desc = "Pantalla", requerido = true,
                    valores = listOf("wifi", "bluetooth", "volumen", "brillo", "general", "notificaciones", "bateria", "aplicaciones", "sonido", "accesibilidad"),
                ),
            ),
        ),
        Herramienta(
            "accion_sistema", "Acciones del sistema: ir a inicio, volver atrás, apps recientes, abrir notificaciones o ajustes rápidos, bloquear la pantalla, hacer una captura.",
            listOf(
                Param(
                    "accion", desc = "Qué hacer", requerido = true,
                    valores = listOf("inicio", "atras", "recientes", "notificaciones", "ajustes_rapidos", "bloquear", "captura"),
                ),
            ),
        ),
        Herramienta("fecha_hora", "Dice la fecha y la hora actuales."),
        Herramienta(
            "recordar", "Guarda un dato personal que el usuario cuenta para acordarte después: su nombre, un apodo o algo que le gusta.",
            listOf(
                Param("clave", desc = "Tipo de dato", requerido = true, valores = listOf("nombre", "apodo", "gusto")),
                Param("valor", desc = "El dato, corto", requerido = true),
            ),
        ),
    )

    fun conNombre(n: String): Herramienta? = herramientas.firstOrNull { it.nombre == n }
}

/**
 * Los modelos pequeños a veces llaman a una herramienta con otro nombre («abrir_app») o con otros argumentos
 * («app» en vez de «nombre»). Aquí se corrige antes de ejecutar.
 */
object Canon {
    private val aliasHerramienta = mapOf(
        "abrir_app" to "abrir_aplicacion", "abrir_programa" to "abrir_aplicacion", "open_app" to "abrir_aplicacion",
        "lanzar_aplicacion" to "abrir_aplicacion", "iniciar_aplicacion" to "abrir_aplicacion", "abrir" to "abrir_aplicacion",
        "subir_volumen" to "volumen", "bajar_volumen" to "volumen", "volume" to "volumen", "set_volume" to "volumen", "control_volumen" to "volumen",
        "brightness" to "brillo", "set_brightness" to "brillo", "cambiar_brillo" to "brillo", "control_brillo" to "brillo",
        "flashlight" to "linterna", "torch" to "linterna", "luz" to "linterna", "flash" to "linterna",
        "llamada" to "llamar", "call" to "llamar", "telefonear" to "llamar",
        "sms" to "enviar_sms", "mandar_sms" to "enviar_sms", "enviar_mensaje" to "enviar_sms", "send_sms" to "enviar_sms",
        "responder" to "responder_mensaje", "contestar" to "responder_mensaje", "contestar_mensaje" to "responder_mensaje", "reply" to "responder_mensaje",
        "mensajes" to "leer_mensajes", "leer_notificaciones" to "leer_mensajes", "read_messages" to "leer_mensajes",
        "poner_alarma" to "alarma", "crear_alarma" to "alarma", "set_alarm" to "alarma",
        "timer" to "temporizador", "poner_temporizador" to "temporizador", "set_timer" to "temporizador", "cronometro" to "temporizador",
        "musica" to "control_multimedia", "multimedia" to "control_multimedia", "media_control" to "control_multimedia",
        "abrir_url" to "abrir_web", "open_url" to "abrir_web", "abrir_pagina" to "abrir_web",
        "buscar" to "buscar_en_web", "google" to "buscar_en_web", "search_web" to "buscar_en_web",
        "abrir_ajustes" to "ajustes", "settings" to "ajustes", "configuracion" to "ajustes",
        "sistema" to "accion_sistema", "global_action" to "accion_sistema", "bloquear_pantalla" to "accion_sistema", "captura_pantalla" to "accion_sistema",
        "hora" to "fecha_hora", "fecha" to "fecha_hora", "get_time" to "fecha_hora",
        "recuerda" to "recordar", "remember" to "recordar",
    )

    private val aliasArgs: Map<String, Map<String, List<String>>> = mapOf(
        "volumen" to mapOf("accion" to listOf("action", "comando", "orden"), "nivel" to listOf("level", "porcentaje", "valor", "value", "volumen"), "tipo" to listOf("type", "stream", "canal")),
        "brillo" to mapOf("accion" to listOf("action", "comando", "orden"), "nivel" to listOf("level", "porcentaje", "valor", "value", "brillo")),
        "linterna" to mapOf("estado" to listOf("accion", "action", "state", "modo")),
        "abrir_aplicacion" to mapOf("nombre" to listOf("app", "aplicacion", "programa", "name", "application", "app_name", "nombre_app")),
        "listar_aplicaciones" to mapOf("filtro" to listOf("filter", "nombre", "buscar", "query")),
        "llamar" to mapOf("destino" to listOf("contacto", "numero", "telefono", "nombre", "to", "phone", "destinatario")),
        "enviar_sms" to mapOf("destinatario" to listOf("contacto", "numero", "telefono", "nombre", "to", "phone", "destino"), "texto" to listOf("mensaje", "text", "message", "contenido", "cuerpo")),
        "responder_mensaje" to mapOf("texto" to listOf("mensaje", "text", "message", "respuesta", "contenido", "reply"), "contacto" to listOf("nombre", "remitente", "destinatario", "persona", "to")),
        "leer_mensajes" to mapOf("contacto" to listOf("nombre", "remitente", "persona", "de")),
        "alarma" to mapOf("hora" to listOf("hour", "horas"), "minutos" to listOf("minutes", "minuto", "min"), "mensaje" to listOf("etiqueta", "label", "texto", "message")),
        "temporizador" to mapOf("segundos" to listOf("seconds", "duracion", "tiempo", "duration"), "mensaje" to listOf("etiqueta", "label", "texto", "message")),
        "control_multimedia" to mapOf("accion" to listOf("action", "comando", "orden", "command")),
        "abrir_web" to mapOf("url" to listOf("link", "enlace", "direccion", "web", "sitio", "pagina")),
        "buscar_en_web" to mapOf("consulta" to listOf("query", "busqueda", "texto", "q", "search", "termino")),
        "ajustes" to mapOf("panel" to listOf("pantalla", "seccion", "section", "tipo", "ajuste", "setting")),
        "accion_sistema" to mapOf("accion" to listOf("action", "comando", "orden", "command")),
        "recordar" to mapOf("clave" to listOf("key", "tipo", "dato"), "valor" to listOf("value", "contenido", "texto")),
    )

    fun claveNorm(s: String): String = Activacion.normalizar(s).replace(' ', '_')

    private fun tieneValor(v: Any?): Boolean = when (v) {
        null -> false
        is String -> v.isNotBlank()
        else -> true
    }

    /** Devuelve el nombre y los argumentos corregidos de una llamada a herramienta. */
    fun llamada(nombre: String, args: Map<String, Any?>?): Pair<String, Map<String, Any?>> {
        val n = claveNorm(nombre)
        val canon = when {
            Esquema.conNombre(n) != null -> n
            aliasHerramienta.containsKey(n) -> aliasHerramienta.getValue(n)
            else -> nombre
        }
        val h = Esquema.conNombre(canon) ?: return canon to (args ?: emptyMap())
        val out = LinkedHashMap<String, Any?>()
        args?.forEach { (k, v) -> out[claveNorm(k)] = v }
        // «subir_volumen»: la acción venía en el nombre
        if (canon == "volumen" && !tieneValor(out["accion"])) {
            if (n == "subir_volumen") out["accion"] = "subir" else if (n == "bajar_volumen") out["accion"] = "bajar"
        }
        if (canon == "accion_sistema" && n == "bloquear_pantalla" && !tieneValor(out["accion"])) out["accion"] = "bloquear"
        if (canon == "accion_sistema" && n == "captura_pantalla" && !tieneValor(out["accion"])) out["accion"] = "captura"
        for ((param, alts) in aliasArgs[canon].orEmpty()) {
            if (tieneValor(out[param])) continue
            for (alt in alts) {
                if (tieneValor(out[alt])) {
                    out[param] = out[alt]
                    out.remove(alt)
                    break
                }
            }
        }
        // tipos: texto donde se espera texto, números donde se esperan números…
        for (p in h.params) {
            val v = out[p.nombre] ?: continue
            when (p.tipo) {
                "string" -> when (v) {
                    is Number, is Boolean -> out[p.nombre] = v.toString()
                    is List<*> -> out[p.nombre] = v.joinToString(" ")
                }
                "integer" -> when (v) {
                    is Number -> out[p.nombre] = v.toInt()
                    is String -> v.trim().replace("%", "").toDoubleOrNull()?.let { out[p.nombre] = it.toInt() }
                }
                "boolean" -> if (v is String) out[p.nombre] = v.trim().lowercase() in setOf("true", "si", "sí", "yes", "1", "verdadero")
            }
            // valores permitidos: se acepta mayúsculas, tildes o espacios («Subir», «Silenciar»…)
            val s = out[p.nombre]
            if (p.valores.isNotEmpty() && s is String) {
                val c = claveNorm(s)
                p.valores.firstOrNull { it == c }?.let { out[p.nombre] = it }
            }
        }
        return canon to out
    }
}
