package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream

class MotorTarBz2 : MotorContenedor {
    override val id: String = "tar.bz2"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.TAR_BZ2

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor =
        contenedorTar(ctx, archivo, nombre, "TAR.BZ2") { bz(archivo) }

    companion object {
        fun bz(f: File): InputStream = BZip2CompressorInputStream(Peek.abrirBuffer(f))
    }
}
