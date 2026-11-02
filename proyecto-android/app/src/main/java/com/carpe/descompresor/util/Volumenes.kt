package com.carpe.descompresor.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Archivos divididos en varios volúmenes: .001/.002…, .z01…+.zip y .part1.rar / .rar+.r00… */
object Volumenes {
    enum class Tipo { NUMERICO, ZIP_DIVIDIDO, RAR }

    class Parte(val uri: Uri, val nombre: String)
    class Plan(val tipo: Tipo, val partes: List<Parte>, val nombreBase: String)

    private val numerico = Regex("^(.+)\\.(\\d{3})$")
    private val zipZ = Regex("^(.+)\\.z(\\d{2})$", RegexOption.IGNORE_CASE)
    private val rarParte = Regex("^(.+)\\.part(\\d+)\\.rar$", RegexOption.IGNORE_CASE)
    private val rarR = Regex("^(.+)\\.r(\\d{2})$", RegexOption.IGNORE_CASE)

    private fun grupo(r: Regex, s: String, i: Int): String = r.find(s)!!.groupValues[i]

    private fun comprobarConsecutivos(numeros: List<Int>, desde: Int, etiqueta: (Int) -> String) {
        for ((i, n) in numeros.withIndex()) {
            if (n != desde + i) throw IOException("Falta el volumen " + etiqueta(desde + i))
        }
    }

    fun planificar(partes: List<Parte>): Plan {
        if (partes.size < 2) throw IOException("Se necesitan al menos dos volúmenes")

        // 1) .001 .002 ... (7-Zip, HJSplit, split)
        if (partes.all { numerico.matches(it.nombre) }) {
            val base = grupo(numerico, partes[0].nombre, 1)
            if (partes.any { grupo(numerico, it.nombre, 1) != base }) {
                throw IOException("Los volúmenes no pertenecen al mismo archivo")
            }
            val ord = partes.sortedBy { grupo(numerico, it.nombre, 2).toInt() }
            comprobarConsecutivos(ord.map { grupo(numerico, it.nombre, 2).toInt() }, 1) { String.format("%03d", it) }
            return Plan(Tipo.NUMERICO, ord, base)
        }

        // 2) .z01 .z02 ... + .zip (WinZip / Info-ZIP)
        val zips = partes.filter { it.nombre.endsWith(".zip", ignoreCase = true) }
        val zs = partes.filter { zipZ.matches(it.nombre) }
        if (zips.size == 1 && zs.isNotEmpty() && zs.size == partes.size - 1) {
            val base = zips[0].nombre.substring(0, zips[0].nombre.length - 4)
            if (zs.any { grupo(zipZ, it.nombre, 1) != base }) throw IOException("Los volúmenes no pertenecen al mismo archivo")
            val ord = zs.sortedBy { grupo(zipZ, it.nombre, 2).toInt() }
            comprobarConsecutivos(ord.map { grupo(zipZ, it.nombre, 2).toInt() }, 1) { "z" + String.format("%02d", it) }
            return Plan(Tipo.ZIP_DIVIDIDO, ord + zips, base)
        }

        // 3a) .part1.rar .part2.rar ...
        if (partes.all { rarParte.matches(it.nombre) }) {
            val base = grupo(rarParte, partes[0].nombre, 1)
            if (partes.any { grupo(rarParte, it.nombre, 1) != base }) throw IOException("Los volúmenes no pertenecen al mismo archivo")
            val ord = partes.sortedBy { grupo(rarParte, it.nombre, 2).toInt() }
            comprobarConsecutivos(ord.map { grupo(rarParte, it.nombre, 2).toInt() }, 1) { "part$it.rar" }
            return Plan(Tipo.RAR, ord, base)
        }

        // 3b) .rar + .r00 .r01 ...
        val rars = partes.filter { it.nombre.endsWith(".rar", ignoreCase = true) && !rarParte.matches(it.nombre) }
        val rs = partes.filter { rarR.matches(it.nombre) }
        if (rars.size == 1 && rs.isNotEmpty() && rs.size == partes.size - 1) {
            val base = rars[0].nombre.substring(0, rars[0].nombre.length - 4)
            if (rs.any { grupo(rarR, it.nombre, 1) != base }) throw IOException("Los volúmenes no pertenecen al mismo archivo")
            val ord = rs.sortedBy { grupo(rarR, it.nombre, 2).toInt() }
            comprobarConsecutivos(ord.map { grupo(rarR, it.nombre, 2).toInt() }, 0) { "r" + String.format("%02d", it) }
            return Plan(Tipo.RAR, rars + ord, base)
        }

        throw IOException(
            "No reconozco estos archivos como volúmenes de un mismo archivo. Formatos admitidos: " +
                ".001/.002…, .z01…+.zip y .part1.rar… o .rar+.r00…"
        )
    }

    private fun seguro(n: String): String = n.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * Copia los volúmenes a caché. NUMERICO: los concatena en un único archivo.
     * ZIP/RAR: los deja juntos, con su nombre, en una subcarpeta de inbox.
     * El nombre devuelto es el del archivo principal.
     */
    fun copiar(
        ctx: Context,
        plan: Plan,
        maxBytes: Long,
        progreso: ((Long, Long) -> Unit)?,
        cancelado: (() -> Boolean)?
    ): CopiaLocal.Resultado {
        var total = 0L
        var conocido = true
        for (p in plan.partes) {
            val t = CopiaLocal.tamanoDe(ctx, p.uri)
            if (t < 0L) conocido = false else total += t
        }
        if (conocido && total > maxBytes) {
            throw IOException("Los volúmenes (${Formato.bytes(total)}) superan el límite de ${Formato.bytes(maxBytes)} (ajustable en Ajustes)")
        }
        val totalGlobal = if (conocido) total else -1L

        val inbox = CopiaLocal.carpetaAbiertos(ctx)
        val concatenado = plan.tipo == Tipo.NUMERICO
        val carpeta: File = if (concatenado) inbox else File(inbox, UUID.randomUUID().toString()).also { it.mkdirs() }
        val unico = File(inbox, UUID.randomUUID().toString() + ".tmp")
        var acumulado = 0L
        var ultimo = 0L
        val creados = ArrayList<File>()
        try {
            val buf = ByteArray(PreferenciasApp(ctx).bufferBytes().coerceAtLeast(32 * 1024))
            var salidaUnica: FileOutputStream? = null
            if (concatenado) {
                salidaUnica = FileOutputStream(unico)
                creados.add(unico)
            }
            try {
                for (p in plan.partes) {
                    val destino: File? = if (concatenado) null else File(carpeta, seguro(p.nombre))
                    val salida: FileOutputStream = salidaUnica ?: FileOutputStream(destino!!)
                    if (destino != null) creados.add(destino)
                    try {
                        val entrada = ctx.contentResolver.openInputStream(p.uri) ?: throw IOException("No se pudo abrir ${p.nombre}")
                        entrada.use { i ->
                            while (true) {
                                if (cancelado != null && cancelado()) throw CopiaCancelada()
                                val n = i.read(buf)
                                if (n < 0) break
                                acumulado += n.toLong()
                                if (acumulado > maxBytes) throw IOException("Los volúmenes superan el límite de ${Formato.bytes(maxBytes)}")
                                salida.write(buf, 0, n)
                                if (progreso != null) {
                                    val ahora = System.currentTimeMillis()
                                    if (ahora - ultimo > 150L) {
                                        ultimo = ahora
                                        progreso(acumulado, totalGlobal)
                                    }
                                }
                            }
                        }
                    } finally {
                        if (salidaUnica == null) salida.close()
                    }
                }
            } finally {
                salidaUnica?.close()
            }
            if (progreso != null) progreso(acumulado, if (totalGlobal > 0L) totalGlobal else acumulado)
        } catch (t: Throwable) {
            if (concatenado) unico.delete() else carpeta.deleteRecursively()
            throw t
        }
        return when (plan.tipo) {
            Tipo.NUMERICO -> CopiaLocal.Resultado(unico, plan.nombreBase)
            Tipo.ZIP_DIVIDIDO -> {
                val p = plan.partes.last()
                CopiaLocal.Resultado(File(carpeta, seguro(p.nombre)), p.nombre)
            }
            Tipo.RAR -> {
                val p = plan.partes.first()
                CopiaLocal.Resultado(File(carpeta, seguro(p.nombre)), p.nombre)
            }
        }
    }
}
