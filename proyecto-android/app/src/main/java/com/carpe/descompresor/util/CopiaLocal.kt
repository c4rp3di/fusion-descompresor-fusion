package com.carpe.descompresor.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class CopiaCancelada : RuntimeException("Apertura cancelada")

object CopiaLocal {
    class Resultado(val archivo: File, val nombre: String)

    /** Carpeta privada y autolimpiable donde viven las copias de los archivos abiertos (cache/inbox). */
    fun carpetaAbiertos(ctx: Context): File {
        val d = File(ctx.cacheDir, "inbox")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun limpiarAbiertos(ctx: Context) {
        carpetaAbiertos(ctx).listFiles()?.forEach { it.deleteRecursively() }
    }

    fun nombreDe(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) {
                        val n = c.getString(i)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            }
        } catch (e: Exception) {
            // se usa el respaldo
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    }

    fun tamanoDe(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i)
                }
            }
        } catch (e: Exception) {
            // desconocido
        }
        return -1L
    }

    /**
     * Copia en streaming la Uri a cache/inbox/<uuid>.tmp. El nombre real se devuelve aparte
     * (el motor lo necesita para distinguir .apk, .tar.gz, etc.).
     */
    fun copiar(
        ctx: Context,
        uri: Uri,
        maxBytes: Long,
        progreso: ((Long, Long) -> Unit)? = null,
        cancelado: (() -> Boolean)? = null
    ): Resultado {
        val nombre = nombreDe(ctx, uri)
        val total = tamanoDe(ctx, uri)
        if (total > maxBytes) {
            throw IOException("El archivo (${Formato.bytes(total)}) supera el límite de ${Formato.bytes(maxBytes)} (ajustable en Ajustes)")
        }
        val destino = File(carpetaAbiertos(ctx), UUID.randomUUID().toString() + ".tmp")
        val entrada = ctx.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
        var hecho = 0L
        var ultimo = 0L
        try {
            entrada.use { i ->
                FileOutputStream(destino).use { o ->
                    val buf = ByteArray(PreferenciasApp(ctx).bufferBytes().coerceAtLeast(32 * 1024))
                    while (true) {
                        if (cancelado != null && cancelado()) throw CopiaCancelada()
                        val n = i.read(buf)
                        if (n < 0) break
                        hecho += n.toLong()
                        if (hecho > maxBytes) {
                            throw IOException("El archivo supera el límite de ${Formato.bytes(maxBytes)} (ajustable en Ajustes)")
                        }
                        o.write(buf, 0, n)
                        if (progreso != null) {
                            val ahora = System.currentTimeMillis()
                            if (ahora - ultimo > 150L) {
                                ultimo = ahora
                                progreso(hecho, total)
                            }
                        }
                    }
                }
            }
            if (progreso != null) progreso(hecho, if (total > 0L) total else hecho)
        } catch (t: Throwable) {
            destino.delete()
            throw t
        }
        return Resultado(destino, nombre)
    }

    /** Borra una copia de caché; si vive en una subcarpeta de inbox (volúmenes), borra la subcarpeta entera. */
    fun borrar(f: File) {
        val padre = f.parentFile
        if (padre != null && padre.name != "inbox") padre.deleteRecursively() else f.delete()
    }

    fun cabecera(archivo: File, n: Int): ByteArray {
        FileInputStream(archivo).use { i ->
            return Flujos.leerHasta(i, n)
        }
    }
}
