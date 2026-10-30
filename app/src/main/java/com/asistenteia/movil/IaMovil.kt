package com.asistenteia.movil

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * IA que se ejecuta DENTRO del móvil (como Ollama, pero en Android): MediaPipe LLM Inference.
 * No necesita clave ni internet para funcionar; solo un archivo de modelo (.task / .litertlm) que se descarga una vez.
 * Se usa por reflexión para que la app compile aunque cambie la versión de la librería.
 */
object IaMovil {
    @Volatile private var motor: Any? = null
    @Volatile private var rutaCargada: String? = null

    fun archivoModelo(ctx: Context): File = File(ctx.filesDir, "modelo-ia.task")
    fun instalado(ctx: Context) = archivoModelo(ctx).let { it.exists() && it.length() > 1_000_000 }

    /** Copia un modelo elegido con el selector de archivos. Llamar fuera del hilo principal. */
    fun importar(ctx: Context, uri: Uri, nombre: (String) -> Unit, progreso: (Long) -> Unit) {
        val dest = File(ctx.filesDir, "modelo-ia.task.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { ent ->
            dest.outputStream().use { sal ->
                val buf = ByteArray(1 shl 16); var total = 0L; var n: Int; var ult = 0L
                while (ent.read(buf).also { n = it } > 0) {
                    sal.write(buf, 0, n); total += n
                    if (total - ult > 8_000_000) { ult = total; progreso(total) }
                }
            }
        }
        nombre(uri.lastPathSegment.orEmpty())
        cerrar()
        archivoModelo(ctx).delete()
        dest.renameTo(archivoModelo(ctx))
    }

    /** Descarga un modelo desde una dirección (p. ej. Hugging Face, enlace «resolve/main/...»). Fuera del hilo principal. */
    fun descargar(ctx: Context, url: String, progreso: (Long, Long) -> Unit) {
        val dest = File(ctx.filesDir, "modelo-ia.task.tmp")
        var u = URL(url)
        require(u.protocol == "https") { "Solo se admiten direcciones https." }
        var c: HttpURLConnection
        var saltos = 0
        while (true) {
            c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            val cod = c.responseCode
            if (cod in 300..399 && saltos++ < 6) {
                u = URL(u, c.getHeaderField("Location")); c.disconnect()
                require(u.protocol == "https") { "Redirección no segura." }
                continue
            }
            if (cod !in 200..299) { c.disconnect(); throw java.io.IOException("El servidor respondió $cod" + if (cod == 401 || cod == 403) " (el modelo puede requerir iniciar sesión o aceptar una licencia; descárgalo en el navegador y usa «Elegir archivo»)." else ".") }
            break
        }
        val total = c.contentLengthLong
        c.inputStream.use { ent ->
            dest.outputStream().use { sal ->
                val buf = ByteArray(1 shl 16); var hecho = 0L; var n: Int; var ult = 0L
                while (ent.read(buf).also { n = it } > 0) {
                    sal.write(buf, 0, n); hecho += n
                    if (hecho - ult > 4_000_000) { ult = hecho; progreso(hecho, total) }
                }
            }
        }
        c.disconnect()
        cerrar()
        archivoModelo(ctx).delete()
        dest.renameTo(archivoModelo(ctx))
        progreso(archivoModelo(ctx).length(), total)
    }

    fun borrar(ctx: Context) { cerrar(); archivoModelo(ctx).delete() }

    @Synchronized fun cerrar() {
        runCatching { motor?.javaClass?.getMethod("close")?.invoke(motor) }
        motor = null; rutaCargada = null
    }

    @Synchronized private fun cargar(ctx: Context): Any {
        val ruta = archivoModelo(ctx).absolutePath
        motor?.let { if (rutaCargada == ruta) return it }
        cerrar()
        val cOpt = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
        val b = cOpt.getMethod("builder").invoke(null)
        val bc = b.javaClass
        bc.getMethod("setModelPath", String::class.java).invoke(b, ruta)
        runCatching { bc.getMethod("setMaxTokens", Int::class.javaPrimitiveType).invoke(b, 1024) }
        val opts = bc.getMethod("build").invoke(b)
        val cLlm = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
        val e = cLlm.getMethod("createFromOptions", Context::class.java, cOpt).invoke(null, ctx.applicationContext, opts)!!
        motor = e; rutaCargada = ruta
        return e
    }

    /** Plantilla de conversación según el modelo (por el nombre del archivo original o por defecto ChatML). */
    private fun formato(sistema: String, usuario: String, familia: String): String = when (familia) {
        "gemma" -> "<start_of_turn>user\n$sistema\n\n$usuario<end_of_turn>\n<start_of_turn>model\n"
        "llama" -> "<|begin_of_text|><|start_of_header_id|>system<|end_of_header_id|>\n\n$sistema<|eot_id|><|start_of_header_id|>user<|end_of_header_id|>\n\n$usuario<|eot_id|><|start_of_header_id|>assistant<|end_of_header_id|>\n\n"
        else -> "<|im_start|>system\n$sistema<|im_end|>\n<|im_start|>user\n$usuario<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Genera una respuesta. Lanza excepción con mensaje claro si falta el modelo. Bloquea: usar en hilo secundario. */
    fun generar(ctx: Context, ajustes: Ajustes, sistema: String, usuario: String): String {
        if (!instalado(ctx)) throw IllegalStateException("MODELO_NO_INSTALADO")
        val prompt = formato(sistema, usuario, ajustes.familiaModelo)
        val r = try {
            val e = cargar(ctx)
            e.javaClass.getMethod("generateResponse", String::class.java).invoke(e, prompt) as? String ?: ""
        } catch (t: Throwable) { // incluye OutOfMemoryError y errores nativos
            cerrar()
            val causa = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            throw ModeloFallo(if (causa is OutOfMemoryError) "memoria insuficiente" else (causa.message ?: causa.javaClass.simpleName))
        }
        return r.replace(Regex("<end_of_turn>|<\\|im_end\\|>|<\\|eot_id\\|>|<eos>"), "").trim()
    }
}

class ModeloFallo(mensaje: String) : Exception(mensaje)
