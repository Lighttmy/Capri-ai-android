package com.asistenteia.movil

/** Cuándo se contesta un mensaje por el usuario y cuándo no. Es la parte de seguridad: no depende de Android. */
enum class ModoRespuesta { NO, BORRADOR, AUTO }

enum class Decision { IGNORAR, BORRADOR, AUTO }

data class Entrante(
    val app: String,
    val remitente: String,
    val texto: String,
    val esGrupo: Boolean,
    val claveConversacion: String,
)

object Politica {
    private val SENSIBLE = Regex(
        "\\b(contrasena|password|clave|pin|iban|tarjeta|cvv|transferencia|transfiere|bizum|dinero|prestame|presta|pago|pagar|pagame|" +
            "cuenta bancaria|banco|urgente|emergencia|hospital|accidente|policia|abogado|contrato|firma|dni|pasaporte|direccion|donde vives|ubicacion)\\b"
    )
    private val CODIGO = Regex("\\b\\d{4,8}\\b")
    private val CODIGO_PALABRAS = Regex("\\b(codigo|code|verificacion|verification|otp|token|un solo uso|no lo compartas|do not share)\\b")
    private val PROPIOS = setOf("tu", "yo", "you", "me")

    /** Mensajes con dinero, claves, datos personales o urgencias: nunca se contestan solos. */
    fun esSensible(texto: String): Boolean = SENSIBLE.containsMatchIn(Activacion.normalizar(texto))

    /** Códigos de verificación de un solo uso y similares: no se contestan ni se leen en voz alta. */
    fun esCodigo(texto: String): Boolean {
        val n = Activacion.normalizar(texto)
        return CODIGO.containsMatchIn(n) && CODIGO_PALABRAS.containsMatchIn(n)
    }

    fun decidir(
        modoApp: ModoRespuesta,
        m: Entrante,
        permitirGrupos: Boolean,
        miNombre: String = "",
    ): Decision {
        if (modoApp == ModoRespuesta.NO) return Decision.IGNORAR
        if (m.texto.isBlank() || m.remitente.isBlank()) return Decision.IGNORAR
        val rem = Activacion.normalizar(m.remitente)
        if (rem in PROPIOS || (miNombre.isNotBlank() && rem == Activacion.normalizar(miNombre))) return Decision.IGNORAR // lo escribí yo
        if (m.esGrupo && !permitirGrupos) return Decision.IGNORAR
        if (esCodigo(m.texto)) return Decision.IGNORAR
        if (esSensible(m.texto)) return Decision.BORRADOR            // nunca solos: los revisa el usuario
        return if (modoApp == ModoRespuesta.AUTO) Decision.AUTO else Decision.BORRADOR
    }

    /** Limpia lo que escribe la IA antes de mandarlo a otra persona. */
    fun limpiarRespuesta(t: String, maxCar: Int = 400, prefijo: String = ""): String {
        var s = t.replace(Regex("[*_`#>]+"), "").trim()
        s = s.replace(Regex("^(respuesta|reply|mensaje)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        s = s.trim().trim('"', '“', '”', '«', '»').trim()
        s = s.replace(Regex("\\s*\\n+\\s*"), " ")
        if (s.length > maxCar) s = s.substring(0, maxCar).trimEnd() + "…"
        return if (s.isBlank()) "" else prefijo + s
    }
}

/** Como mucho N respuestas por conversación en una ventana de tiempo, y nunca dos veces al mismo mensaje. */
class LimitadorRespuestas(private val maxPorVentana: Int = 3, private val ventanaMs: Long = 60 * 60 * 1000L) {
    private val envios = HashMap<String, ArrayDeque<Long>>()
    private val vistos = LinkedHashMap<String, Long>()

    @Synchronized
    fun permite(conversacion: String, mensajeId: String, ahoraMs: Long): Boolean {
        vistos.entries.removeAll { ahoraMs - it.value > ventanaMs }
        if (vistos.containsKey(mensajeId)) return false
        val q = envios.getOrPut(conversacion) { ArrayDeque() }
        while (q.isNotEmpty() && ahoraMs - q.first() > ventanaMs) q.removeFirst()
        return q.size < maxPorVentana
    }

    @Synchronized
    fun registrar(conversacion: String, mensajeId: String, ahoraMs: Long) {
        vistos[mensajeId] = ahoraMs
        envios.getOrPut(conversacion) { ArrayDeque() }.addLast(ahoraMs)
    }

    /** Marca un mensaje como ya tratado (aunque no se haya contestado). */
    @Synchronized
    fun marcarVisto(mensajeId: String, ahoraMs: Long) { vistos[mensajeId] = ahoraMs }

    @Synchronized
    fun yaVisto(mensajeId: String) = vistos.containsKey(mensajeId)
}
