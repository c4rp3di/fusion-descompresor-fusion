package com.carpe.descompresor.crear

import android.content.Context
import android.net.Uri
import com.carpe.descompresor.util.CopiaLocal
import com.carpe.descompresor.util.Flujos
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CreadorZip {
    /** Crea un ZIP con los archivos elegidos (nombre plano). nivel 0..9. */
    fun crear(ctx: Context, uris: List<Uri>, salida: OutputStream, nivel: Int) {
        ZipOutputStream(salida.buffered()).use { z ->
            z.setLevel(nivel.coerceIn(0, 9))
            val usados = HashSet<String>()
            for (u in uris) {
                val nombre = unico(CopiaLocal.nombreDe(ctx, u), usados)
                z.putNextEntry(ZipEntry(nombre))
                val i = ctx.contentResolver.openInputStream(u) ?: throw IOException("No se pudo abrir $nombre")
                i.use { Flujos.copiar(it, z, 64 * 1024) }
                z.closeEntry()
            }
        }
    }

    /** Comprime una carpeta completa conservando la estructura (para compartir lo extraído). */
    fun crearDesdeCarpeta(carpeta: File, destino: File) {
        ZipOutputStream(destino.outputStream().buffered()).use { z ->
            val base = carpeta.absolutePath.length + 1
            carpeta.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.absolutePath.substring(base).replace('\\', '/')))
                FileInputStream(f).use { Flujos.copiar(it, z, 64 * 1024) }
                z.closeEntry()
            }
        }
    }

    internal fun unico(n: String, usados: MutableSet<String>): String {
        var cand = n
        var i = 1
        while (!usados.add(cand)) {
            val p = n.lastIndexOf('.')
            cand = if (p > 0) n.substring(0, p) + " (" + i + ")" + n.substring(p) else "$n ($i)"
            i++
        }
        return cand
    }
}
