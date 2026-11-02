package com.carpe.descompresor.crear

import android.content.Context
import android.net.Uri
import com.carpe.descompresor.util.CopiaLocal
import com.carpe.descompresor.util.Flujos
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

object CreadorTar {
    fun crear(ctx: Context, uris: List<Uri>, salida: OutputStream, comprimirGz: Boolean) {
        val base: OutputStream = if (comprimirGz) GZIPOutputStream(salida.buffered()) else salida.buffered()
        TarArchiveOutputStream(base).use { t ->
            t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val usados = HashSet<String>()
            for (u in uris) {
                val nombre = CreadorZip.unico(CopiaLocal.nombreDe(ctx, u), usados)
                var tam = CopiaLocal.tamanoDe(ctx, u)
                var temporal: File? = null
                if (tam < 0L) {
                    // tamaño desconocido: TAR necesita conocerlo; se copia antes a caché
                    val f = File.createTempFile("tar_", ".tmp", ctx.cacheDir)
                    ctx.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> Flujos.copiar(i, o, 64 * 1024) } }
                    tam = f.length()
                    temporal = f
                }
                val e = TarArchiveEntry(nombre)
                e.size = tam
                t.putArchiveEntry(e)
                val entrada = if (temporal != null) temporal.inputStream() else ctx.contentResolver.openInputStream(u)
                    ?: throw IOException("No se pudo abrir $nombre")
                entrada.use { Flujos.copiar(it, t, 64 * 1024) }
                t.closeArchiveEntry()
                temporal?.delete()
            }
            t.finish()
        }
    }
}
