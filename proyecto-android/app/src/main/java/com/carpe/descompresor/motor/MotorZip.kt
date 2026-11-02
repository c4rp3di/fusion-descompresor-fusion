package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

open class ContenedorZip(
    nombre: String,
    formato: String,
    archivo: File,
    maxEntradas: Int,
    val dividido: Boolean = false
) : Contenedor(nombre, formato, archivo) {

    val zip: ZipFile = if (dividido) {
        ZipFile(ZipSplitReadOnlySeekableByteChannel.buildFromLastSplitSegment(archivo))
    } else {
        ZipFile(archivo)
    }
    private val mapa = HashMap<String, ZipArchiveEntry>()
    override val entradas: List<Entrada>

    init {
        val lista = ArrayList<Entrada>()
        try {
            val en = zip.entries
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (lista.size >= maxEntradas) {
                    throw IllegalStateException("Demasiadas entradas (más de $maxEntradas; ajustable en Ajustes)")
                }
                val cifrada = e.generalPurposeBit.usesEncryption() || !zip.canReadEntryData(e)
                val metodo = when (e.method) {
                    0 -> "Stored"
                    8 -> "Deflate"
                    9 -> "Deflate64"
                    12 -> "BZip2"
                    14 -> "LZMA"
                    93 -> "Zstd"
                    99 -> "AES"
                    else -> "Método ${e.method}"
                }
                val t = e.time
                lista.add(
                    Entrada(
                        ruta = e.name,
                        esDirectorio = e.isDirectory,
                        tamano = e.size,
                        tamanoComprimido = e.compressedSize,
                        metodo = metodo,
                        crc = e.crc,
                        fecha = if (t > 0L) t else 0L,
                        cifrada = cifrada,
                        atributos = if (e.unixMode != 0) Integer.toOctalString(e.unixMode and 0xFFF) else ""
                    )
                )
                mapa[e.name] = e
            }
        } catch (t: Throwable) {
            try { zip.close() } catch (x: Exception) { }
            throw t
        }
        entradas = lista
    }

    override fun abrirEntrada(entrada: Entrada): InputStream {
        val e = mapa[entrada.ruta] ?: throw FileNotFoundException(entrada.ruta)
        return zip.getInputStream(e)
    }

    override fun close() {
        try { zip.close() } catch (x: Exception) { }
    }
}

/** ¿Se puede editar (añadir, borrar, renombrar, guardar) este contenedor? Solo ZIP/APK de un único archivo. */
object EdicionZip {
    fun editable(c: Contenedor?): Boolean = c is ContenedorZip && !c.dividido
}

class ContenedorApk(nombre: String, archivo: File, maxEntradas: Int) :
    ContenedorZip(nombre, "APK", archivo, maxEntradas) {
    override val esApk: Boolean
        get() = true
}

class MotorZip : MotorContenedor {
    override val id: String = "zip"

    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.ZIP

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        return ContenedorZip(nombre, "ZIP", archivo, lim.maxEntradas)
    }
}
