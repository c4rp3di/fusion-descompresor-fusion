package com.carpe.descompresor.util

data class Limites(
    val maxContenedorBytes: Long,
    val maxEntradaBytes: Long,
    val maxTotalBytes: Long,
    val ratioMax: Int,
    val maxEntradas: Int
)

object Seguridad {
    private val unidadWindows = Regex("^[A-Za-z]:")

    /** Devuelve la ruta normalizada o null si es insegura (.., absoluta, letra de unidad, controles). */
    fun limpiarRuta(ruta: String): String? {
        val r = ruta.replace('\\', '/')
        if (r.startsWith("/") || unidadWindows.containsMatchIn(r)) return null
        val partes = ArrayList<String>()
        for (p in r.split('/')) {
            if (p.isEmpty() || p == ".") continue
            if (p == "..") return null
            if (p.any { it.code < 32 }) return null
            partes.add(p)
        }
        return if (partes.isEmpty()) null else partes.joinToString("/")
    }

    fun limites(p: PreferenciasApp): Limites = Limites(
        maxContenedorBytes = p.entero("max_contenedor_mb").toLong() * 1024L * 1024L,
        maxEntradaBytes = p.entero("max_entrada_mb").toLong() * 1024L * 1024L,
        maxTotalBytes = p.entero("max_total_mb").toLong() * 1024L * 1024L,
        ratioMax = p.entero("ratio_max").coerceAtLeast(1),
        maxEntradas = p.entero("max_entradas").coerceAtLeast(1)
    )

    fun ratioExcesivo(tamano: Long, comprimido: Long, max: Int): Boolean {
        if (tamano <= 0L || comprimido <= 0L) return false
        return tamano / comprimido > max.toLong()
    }
}
