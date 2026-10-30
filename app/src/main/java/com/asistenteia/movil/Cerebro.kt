package com.asistenteia.movil

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lo que devuelve el cerebro: el texto que se dice en voz alta y las acciones que hizo. */
data class Resultado(val texto: String, val acciones: List<String> = emptyList(), val ok: Boolean = true)

/**
 * El «cerebro»: Claude (con clave de API) o una IA local en tu PC (Ollama, por la red wifi).
 * Siempre se llama desde un hilo secundario (hace peticiones de red).
 */
class Cerebro(private val ctx: Context, private val ajustes: Ajustes, private val herr: Herramientas) {
    private val histClaude = ArrayList<JSONObject>()
    private val histOllama = ArrayList<JSONObject>()
    private var ultimoUso = 0L

    private fun sistema(): String {
        val ahora = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "ES")).format(Date())
        val quien = ajustes.nombre.ifBlank { "el usuario" }
        val extra = buildString {
            if (ajustes.nombre.isNotBlank()) append(" Se llama ${ajustes.nombre}.")
            if (ajustes.apodo.isNotBlank()) append(" Le gusta que le llames «${ajustes.apodo}».")
            if (ajustes.gustos.isNotBlank()) append(" Le gusta: ${ajustes.gustos}.")
        }
        return "Eres «Asistente», el asistente de voz del móvil de $quien.$extra " +
            "Hablas siempre en español, con un tono cercano y de confianza. Tus respuestas se leen en voz alta: " +
            "usa una o dos frases cortas, sin listas, sin emojis y sin markdown. " +
            "Para hacer lo que te pide (volumen, brillo, abrir apps, mensajes, alarmas…) usa las herramientas, " +
            "directamente y sin pedir permiso ni hacer preguntas innecesarias; si falta un detalle menor, elige lo más razonable. " +
            "Después confirma en una frase corta lo que hiciste. Si una herramienta falla, dilo con sencillez y di qué falta. " +
            "El usuario habla por voz: puede haber pequeños errores de transcripción, interpreta la intención. " +
            "No leas en voz alta códigos de verificación ni contraseñas. Ahora es $ahora."
    }

    @Synchronized
    fun conversar(texto: String): Resultado {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoUso > 5 * 60_000L) { histClaude.clear(); histOllama.clear() } // otra conversación
        ultimoUso = ahora
        return try {
            when (ajustes.proveedor) {
                "ollama" -> conversarOllama(texto)
                "claude" -> conversarClaude(texto)
                "gratis" -> conversarLocal(texto) ?: conversarGratis(texto)
                "movil" -> conversarLocal(texto) ?: conversarMovil(texto)
                else -> conversarLocal(texto) ?: Resultado(Local.NO_ENTENDIDO, ok = false)
            }
        } catch (e: Exception) {
            // si falla la red o la clave, las órdenes básicas siguen funcionando sin IA
            runCatching { conversarLocal(texto) }.getOrNull() ?: Resultado(mensajeError(e), ok = false)
        }
    }

    /** Una sola petición sin herramientas ni historial: sirve para redactar la respuesta a un mensaje. */
    fun generar(sistema: String, usuario: String): String {
        if (ajustes.proveedor == "local") return "" // sin IA no se redactan respuestas
        if (ajustes.proveedor == "movil") return if (IaMovil.instalado(ctx)) IaMovil.generar(ctx, ajustes, sistema, usuario) else ""
        if (ajustes.proveedor == "gratis") {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sistema))
                .put(JSONObject().put("role", "user").put("content", usuario))
            return chatGratis(msgs)
        }
        return if (ajustes.proveedor == "ollama") {
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", sistema))
                .put(JSONObject().put("role", "user").put("content", usuario))
            val body = JSONObject().put("model", ajustes.modeloOllama).put("stream", false).put("messages", msgs)
            val r = post(ajustes.urlOllama + "/api/chat", body, emptyMap(), 120_000)
            r.optJSONObject("message")?.optString("content").orEmpty().trim()
        } else {
            val body = JSONObject().put("model", ajustes.modeloClaude).put("max_tokens", 300).put("system", sistema)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", usuario)))
            val r = post(ANTHROPIC_URL, body, cabecerasClaude(), 60_000)
            textoClaude(r.optJSONArray("content"))
        }
    }

    // ------------------------------------------------------------------ Claude

    private fun cabecerasClaude() = mapOf("x-api-key" to ajustes.claveApi, "anthropic-version" to "2023-06-01")

    private fun herramientasClaude(): JSONArray {
        val a = JSONArray()
        for (h in Esquema.herramientas) a.put(JSONObject().put("name", h.nombre).put("description", h.desc).put("input_schema", esquemaJson(h)))
        return a
    }

    private fun esquemaJson(h: Herramienta): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for (p in h.params) {
            val o = JSONObject().put("type", p.tipo).put("description", p.desc)
            if (p.valores.isNotEmpty()) o.put("enum", JSONArray(p.valores))
            props.put(p.nombre, o)
            if (p.requerido) req.put(p.nombre)
        }
        return JSONObject().put("type", "object").put("properties", props).put("required", req)
    }

    private fun textoClaude(c: JSONArray?): String {
        val sb = StringBuilder()
        if (c != null) for (i in 0 until c.length()) {
            val b = c.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim()
    }

    private fun conversarClaude(texto: String): Resultado {
        if (ajustes.claveApi.isBlank()) return Resultado("Falta la clave de Claude. Escríbela en los ajustes de la aplicación.", ok = false)
        recortar(histClaude)
        histClaude.add(JSONObject().put("role", "user").put("content", texto))
        val acciones = ArrayList<String>()
        for (vuelta in 0 until MAX_VUELTAS) {
            val body = JSONObject().put("model", ajustes.modeloClaude).put("max_tokens", 700).put("system", sistema())
                .put("tools", herramientasClaude()).put("messages", JSONArray(histClaude))
            val r = post(ANTHROPIC_URL, body, cabecerasClaude(), 60_000)
            val contenido = r.optJSONArray("content") ?: JSONArray()
            histClaude.add(JSONObject().put("role", "assistant").put("content", contenido))
            if (r.optString("stop_reason") != "tool_use") return Resultado(textoClaude(contenido).ifBlank { "Hecho." }, acciones)
            val resultados = JSONArray()
            for (i in 0 until contenido.length()) {
                val b = contenido.getJSONObject(i)
                if (b.optString("type") != "tool_use") continue
                val nombre = b.optString("name")
                val salida = ejecutar(nombre, b.optJSONObject("input"), acciones)
                resultados.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", salida))
            }
            histClaude.add(JSONObject().put("role", "user").put("content", resultados))
        }
        return Resultado("Lo he intentado varias veces y no lo he conseguido.", acciones, ok = false)
    }

    // ------------------------------------------------------------------ Ollama (IA local en el PC)

    private fun herramientasOllama(): JSONArray {
        val a = JSONArray()
        for (h in Esquema.herramientas) {
            a.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", h.nombre).put("description", h.desc).put("parameters", esquemaJson(h))))
        }
        return a
    }

    private fun conversarOllama(texto: String): Resultado {
        recortar(histOllama)
        histOllama.add(JSONObject().put("role", "user").put("content", texto))
        val acciones = ArrayList<String>()
        for (vuelta in 0 until MAX_VUELTAS) {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sistema()))
            histOllama.forEach { msgs.put(it) }
            val body = JSONObject().put("model", ajustes.modeloOllama).put("stream", false)
                .put("messages", msgs).put("tools", herramientasOllama())
            val r = post(ajustes.urlOllama + "/api/chat", body, emptyMap(), 180_000)
            val m = r.optJSONObject("message") ?: return Resultado("La IA del PC no respondió bien.", acciones, ok = false)
            histOllama.add(m)
            val llamadas = m.optJSONArray("tool_calls")
            if (llamadas == null || llamadas.length() == 0) return Resultado(m.optString("content").trim().ifBlank { "Hecho." }, acciones)
            for (i in 0 until llamadas.length()) {
                val f = llamadas.getJSONObject(i).optJSONObject("function") ?: continue
                val args = f.opt("arguments").let { if (it is String) runCatching { JSONObject(it) }.getOrNull() else it as? JSONObject }
                val salida = ejecutar(f.optString("name"), args, acciones)
                histOllama.add(JSONObject().put("role", "tool").put("content", salida))
            }
        }
        return Resultado("Lo he intentado varias veces y no lo he conseguido.", acciones, ok = false)
    }

    // ------------------------------------------------------------------ sin clave

    /** Órdenes y charla básicas, sin IA ni internet. null = no lo reconozco. */
    private fun conversarLocal(texto: String): Resultado? {
        val orden = Local.interpretar(texto)
        if (orden != null) {
            val acciones = ArrayList<String>()
            val mapa = HashMap<String, Any?>(orden.second)
            val salida = ejecutar(orden.first, org.json.JSONObject(mapa as Map<*, *>), acciones)
            return Resultado(salida.ifBlank { "Hecho." }, acciones)
        }
        return Local.charla(texto)?.let { Resultado(it) }
    }

    private val histMovil = ArrayList<String>()

    /** Charla con la IA que corre dentro del móvil (sin internet ni clave). Las acciones las resuelve antes `Local`. */
    private fun conversarMovil(texto: String): Resultado {
        if (!IaMovil.instalado(ctx)) return Resultado(Local.NO_ENTENDIDO + " Para charlar libremente, instala el modelo de IA desde la pantalla de la aplicación.", ok = false)
        if (System.currentTimeMillis() - ultimoUso > 5 * 60_000L) histMovil.clear()
        while (histMovil.size > 6) histMovil.removeAt(0)
        val charla = histMovil.joinToString("\n")
        val u = (if (charla.isNotEmpty()) "Conversación hasta ahora:\n$charla\n\n" else "") + "Usuario: $texto"
        val s = sistema().substringBefore(" Para hacer lo que te pide") + " Responde breve y en español. No puedes manejar el móvil en esta respuesta: si te piden una acción, di que lo pidan con otras palabras, como «sube el volumen»."
        val r = Politica.limpiarRespuesta(IaMovil.generar(ctx, ajustes, s, u), 400)
        if (r.isBlank()) return Resultado("No se me ocurre qué decir.", ok = false)
        histMovil.add("Usuario: $texto"); histMovil.add("Asistente: $r")
        return Resultado(r)
    }

    private val histGratis = ArrayList<JSONObject>()

    /** Charla con un servicio gratuito sin clave (Pollinations). Experimental: solo conversación, sin herramientas. */
    private fun conversarGratis(texto: String): Resultado {
        if (System.currentTimeMillis() - ultimoUso > 5 * 60_000L) histGratis.clear()
        recortar(histGratis)
        histGratis.add(JSONObject().put("role", "user").put("content", texto))
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sistema() + " En este modo no puedes manejar el móvil: si te piden una acción que no sea charla, di que prueben a pedirlo con otras palabras, por ejemplo «sube el volumen»."))
        histGratis.forEach { msgs.put(it) }
        val r = chatGratis(msgs)
        if (r.isBlank()) return Resultado("El chat gratuito no respondió. Las órdenes básicas siguen funcionando.", ok = false)
        histGratis.add(JSONObject().put("role", "assistant").put("content", r))
        return Resultado(r)
    }

    private fun chatGratis(msgs: JSONArray): String {
        val body = JSONObject().put("model", "openai").put("messages", msgs)
        val r = post(GRATIS_URL, body, emptyMap(), 60_000)
        return r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
    }

    // ------------------------------------------------------------------ común

    private fun ejecutar(nombre: String, input: JSONObject?, acciones: MutableList<String>): String {
        val mapa = HashMap<String, Any?>()
        input?.keys()?.forEach { k -> mapa[k] = input.opt(k) }
        val (n, a) = Canon.llamada(nombre, mapa)
        acciones.add(n)
        return try { herr.ejecutar(n, a) } catch (e: Exception) { "Error al ejecutar $n: ${e.message}" }
    }

    /** Se queda con las últimas vueltas de conversación, cortando siempre en un mensaje del usuario con texto. */
    private fun recortar(h: MutableList<JSONObject>) {
        while (h.size > MAX_HISTORIAL) {
            h.removeAt(0)
            while (h.isNotEmpty() && !(h[0].optString("role") == "user" && h[0].opt("content") is String)) h.removeAt(0)
        }
    }

    private fun post(url: String, cuerpo: JSONObject, cab: Map<String, String>, lecturaMs: Int): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = lecturaMs
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            cab.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = c.responseCode
            val flujo = if (codigo in 200..299) c.inputStream else c.errorStream
            val texto = flujo?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (codigo !in 200..299) throw ErrorHttp(codigo, texto)
            return JSONObject(texto)
        } finally {
            c.disconnect()
        }
    }

    class ErrorHttp(val codigo: Int, val cuerpo: String) : Exception("HTTP $codigo")

    private fun mensajeError(e: Exception): String = when {
        e is ErrorHttp && (e.codigo == 401 || e.codigo == 403) -> "La clave de Claude no es válida. Revísala en los ajustes."
        e is ErrorHttp && e.codigo == 429 -> "He llegado al límite de uso de Claude. Prueba en un rato."
        e is ErrorHttp && e.codigo == 404 && ajustes.proveedor == "ollama" -> "El PC responde, pero no tiene el modelo ${ajustes.modeloOllama}. Instálalo con ollama pull."
        e is ErrorHttp -> "El servicio de IA dio un error ${e.codigo}."
        e is java.net.SocketTimeoutException -> "La IA tardó demasiado en responder."
        e is java.net.UnknownHostException || e is java.net.ConnectException ->
            if (ajustes.proveedor == "ollama") "No consigo conectar con tu PC en ${ajustes.urlOllama}. Comprueba que está encendido, en la misma wifi y con Ollama abierto."
            else "No tengo conexión a internet."
        e is IllegalStateException && e.message == "MODELO_NO_INSTALADO" -> "Falta instalar el modelo de IA en el móvil."
        e is ClassNotFoundException || e is java.lang.reflect.InvocationTargetException || e is ModeloFallo -> "No he podido arrancar la IA del móvil: ${e.cause?.message ?: e.message}. Prueba con otro modelo (más pequeño)."
        else -> "No he podido pensar la respuesta: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        const val GRATIS_URL = "https://text.pollinations.ai/openai"
        const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
        const val MAX_VUELTAS = 6
        const val MAX_HISTORIAL = 24
    }
}
