package com.asistenteia.movil

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Ajustes guardados en el móvil (SharedPreferences privadas de la app). */
class Ajustes(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    // ---- cerebro
    var proveedor: String
        get() = s("proveedor", "movil")           // "movil" (IA en el propio móvil) | "local" (sin IA ni clave) | "gratis" | "ollama" | "claude"
        set(v) = put("proveedor", v)
    var claveApi: String
        get() = s("claveApi", "")
        set(v) = put("claveApi", v.trim())
    var modeloClaude: String
        get() = s("modeloClaude", "claude-sonnet-5-5")
        set(v) = put("modeloClaude", v.trim())
    var urlOllama: String
        get() = s("urlOllama", "http://192.168.1.100:11434")
        set(v) = put("urlOllama", v.trim().trimEnd('/'))
    var modeloOllama: String
        get() = s("modeloOllama", "qwen2.5:3b")
        set(v) = put("modeloOllama", v.trim())

    /** Familia de plantilla del modelo instalado: "chatml" (Qwen y similares), "gemma" o "llama". */
    var familiaModelo: String
        get() = s("familiaModelo", "chatml")
        set(v) = put("familiaModelo", v)
    var urlModelo: String
        get() = s("urlModelo", "")
        set(v) = put("urlModelo", v.trim())

    // ---- voz
    var frase: String
        get() = s("frase", "oye asistente")
        set(v) = put("frase", v.trim())
    var tonoVoz: Float
        get() = sp.getFloat("tonoVoz", 1.1f)
        set(v) = sp.edit().putFloat("tonoVoz", v).apply()
    var velocidadVoz: Float
        get() = sp.getFloat("velocidadVoz", 1.0f)
        set(v) = sp.edit().putFloat("velocidadVoz", v).apply()
    var silenciarPitidos: Boolean
        get() = b("silenciarPitidos", true)
        set(v) = put("silenciarPitidos", v)

    // ---- el usuario
    var nombre: String
        get() = s("nombre", "")
        set(v) = put("nombre", v.trim())
    var apodo: String
        get() = s("apodo", "")
        set(v) = put("apodo", v.trim())
    var gustos: String
        get() = s("gustos", "")
        set(v) = put("gustos", v.trim())

    // ---- mensajes
    var estiloRespuesta: String
        get() = s("estiloRespuesta", "Breve, amable y natural, como escribiría yo. Sin formalidades.")
        set(v) = put("estiloRespuesta", v.trim())
    var permitirGrupos: Boolean
        get() = b("permitirGrupos", false)
        set(v) = put("permitirGrupos", v)
    var avisarIA: Boolean
        get() = b("avisarIA", true)
        set(v) = put("avisarIA", v)

    /** Modo de respuesta de cada aplicación de mensajería (por paquete). Por defecto, desactivado. */
    fun modoDe(paquete: String): ModoRespuesta {
        val j = runCatching { JSONObject(s("modos", "{}")) }.getOrDefault(JSONObject())
        return runCatching { ModoRespuesta.valueOf(j.optString(paquete, "NO")) }.getOrDefault(ModoRespuesta.NO)
    }

    fun ponerModo(paquete: String, modo: ModoRespuesta) {
        val j = runCatching { JSONObject(s("modos", "{}")) }.getOrDefault(JSONObject())
        j.put(paquete, modo.name)
        put("modos", j.toString())
    }

    // ---- estado
    var activo: Boolean
        get() = b("activo", false)
        set(v) = put("activo", v)
}

/** Aplicaciones de mensajería que se pueden contestar (paquete → nombre). */
val APPS_MENSAJERIA = linkedMapOf(
    "com.whatsapp" to "WhatsApp",
    "com.whatsapp.w4b" to "WhatsApp Business",
    "org.telegram.messenger" to "Telegram",
    "com.google.android.apps.messaging" to "Mensajes (Google)",
    "com.samsung.android.messaging" to "Mensajes (Samsung)",
    "org.thoughtcrime.securesms" to "Signal",
    "com.facebook.orca" to "Messenger",
    "com.instagram.android" to "Instagram",
)
