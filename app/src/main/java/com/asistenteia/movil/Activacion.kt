package com.asistenteia.movil

import java.text.Normalizer
import kotlin.math.max

/**
 * Palabra de activación: busca la frase («oye asistente») dentro de lo que ha reconocido la voz, tolerando
 * los fallos típicos del reconocimiento: «oye» mal oído («hoy», «ey»…), palabras partidas o pegadas,
 * letras comidas, o que falte el «oye» pero se oiga la palabra clave.
 * (Es el mismo algoritmo que usa la versión de PC.)
 */
object Activacion {
    private val PREVIAS = setOf("oye", "hey", "ey", "ei", "oi", "hoy", "hoye", "oiga", "ojo", "eh", "ok", "okay", "hola", "ola")

    fun normalizar(t: String?): String {
        val s = Normalizer.normalize((t ?: "").lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return s
    }

    /** Similitud 0..1 basada en la distancia de edición. */
    fun similitud(a: String, b: String): Double {
        if (a == b) return 1.0
        val m = a.length
        val n = b.length
        if (m == 0 || n == 0) return 0.0
        var prev = IntArray(n + 1) { it }
        for (i in 1..m) {
            val cur = IntArray(n + 1)
            cur[0] = i
            for (j in 1..n) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return 1.0 - prev[n].toDouble() / max(m, n)
    }

    /** Si el texto contiene la frase devuelve lo que viene después ("" si nada); si no, null. */
    fun buscar(texto: String?, frase: String, umbral: Double = 0.8): String? {
        val pares = (texto ?: "").trim().split(Regex("\\s+"))
            .map { it to normalizar(it) }
            .filter { it.second.isNotEmpty() }
        val clave = normalizar(frase).split(" ").filter { it.isNotEmpty() }
        if (clave.isEmpty() || pares.isEmpty()) return null
        val objetivo = clave.joinToString("")
        val n = clave.size
        // 1) la frase entera, con una palabra de más o de menos (por palabras partidas o pegadas)
        for (i in pares.indices) {
            for (tam in intArrayOf(n, n + 1, n - 1, n + 2)) {
                if (tam < 1) continue
                if (i + tam > pares.size) continue
                val v = pares.subList(i, i + tam)
                val cand = v.joinToString("") { it.second }
                if (cand == objetivo || similitud(cand, objetivo) >= umbral) {
                    // si no está todo pegado, la primera palabra debe parecerse al «oye» (así «el asistente» no vale)
                    if (n >= 2 && tam >= n && v[0].second !in PREVIAS && similitud(v[0].second, clave[0]) < 0.4) continue
                    return pares.subList(i + tam, pares.size).joinToString(" ") { it.first }
                }
            }
        }
        // 2) solo la palabra distintiva (la última de la frase), si es larga y va donde se espera el «oye»
        if (n >= 2) {
            val dist = clave[n - 1]
            if (dist.length >= 5) {
                for (i in pares.indices) {
                    for (tam in intArrayOf(1, 2)) {
                        if (i + tam > pares.size) continue
                        val v = pares.subList(i, i + tam)
                        val cand = v.joinToString("") { it.second }
                        if (tam == 2 && similitud(v[1].second, dist) >= 0.9) continue // «el asistente»: ya está entera
                        if (similitud(cand, dist) < 0.72) continue
                        val prev = if (i > 0) pares[i - 1].second else ""
                        val ok = i == 0 || prev in PREVIAS || similitud(prev, clave[0]) >= 0.5 || pares.size <= 2
                        if (ok) return pares.subList(i + tam, pares.size).joinToString(" ") { it.first }
                    }
                }
            }
        }
        return null
    }
}
