package com.carpe.descompresor.motor

import com.carpe.descompresor.data.Entrada
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Lectores secuenciales concretos sobre commons-compress. */

class LectorTar(private val tar: TarArchiveInputStream) : LectorSecuencial {
    override fun siguiente(): Entrada? {
        while (true) {
            val e = tar.nextTarEntry ?: return null
            if (e.isDirectory) {
                return Entrada(e.name, true, 0L, -1L, "tar", -1L, e.modTime?.time ?: 0L, false, Integer.toOctalString(e.mode and 0xFFF))
            }
            if (e.isFile) {
                return Entrada(e.name, false, e.size, e.size, "tar", -1L, e.modTime?.time ?: 0L, false, Integer.toOctalString(e.mode and 0xFFF))
            }
            // enlaces, dispositivos...: se ignoran
        }
    }

    override fun flujo(): InputStream = tar
    override fun close() = tar.close()
}

class LectorCpio(private val cpio: CpioArchiveInputStream) : LectorSecuencial {
    override fun siguiente(): Entrada? {
        while (true) {
            val e = cpio.nextCPIOEntry ?: return null
            if (e.isDirectory) return Entrada(e.name, true, 0L, -1L, "cpio", -1L, 0L)
            if (e.isRegularFile) return Entrada(e.name, false, e.size, e.size, "cpio", -1L, 0L)
        }
    }

    override fun flujo(): InputStream = cpio
    override fun close() = cpio.close()
}

class LectorAr(private val ar: ArArchiveInputStream) : LectorSecuencial {
    override fun siguiente(): Entrada? {
        val e = ar.nextArEntry ?: return null
        return Entrada(e.name, e.isDirectory, e.length, e.length, "ar", -1L, 0L)
    }

    override fun flujo(): InputStream = ar
    override fun close() = ar.close()
}

class LectorSevenZ(private val sz: SevenZFile) : LectorSecuencial {
    override fun siguiente(): Entrada? {
        while (true) {
            val e = sz.nextEntry ?: return null
            if (e.isAntiItem) continue
            val crc = if (e.hasCrc) e.crcValue else -1L
            return Entrada(e.name, e.isDirectory, e.size, -1L, "7z", crc, 0L)
        }
    }

    override fun flujo(): InputStream = object : InputStream() {
        override fun read(): Int = sz.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
    }

    override fun close() = sz.close()
}

/** Un único archivo comprimido (.gz, .bz2, .xz). */
class LectorUnico(
    private val flujoUnico: InputStream,
    private val nombreSalida: String,
    private val metodo: String
) : LectorSecuencial {
    private var entregado = false

    override fun siguiente(): Entrada? {
        if (entregado) return null
        entregado = true
        return Entrada(nombreSalida, false, -1L, -1L, metodo, -1L, 0L)
    }

    override fun flujo(): InputStream = flujoUnico
    override fun close() = flujoUnico.close()
}

object Peek {
    fun abrirBuffer(f: File): InputStream = BufferedInputStream(FileInputStream(f), 64 * 1024)

    /** ¿El contenido (ya descomprimido) parece un TAR ustar? */
    fun pareceTar(abrir: () -> InputStream): Boolean {
        return try {
            abrir().use { s ->
                val b = ByteArray(512)
                var n = 0
                while (n < 512) {
                    val r = s.read(b, n, 512 - n)
                    if (r < 0) break
                    n += r
                }
                n >= 262 && String(b, 257, 5, Charsets.US_ASCII) == "ustar"
            }
        } catch (e: Exception) {
            false
        }
    }

    fun nombreSinExtension(nombre: String, extensiones: List<String>): String {
        val l = nombre.lowercase()
        for (x in extensiones) {
            if (l.endsWith(x)) return nombre.substring(0, nombre.length - x.length)
        }
        return nombre + ".out"
    }
}
