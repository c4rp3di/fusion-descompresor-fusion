package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream

class MotorTarXz : MotorContenedor {
    override val id: String = "tar.xz"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.TAR_XZ

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor =
        contenedorTar(ctx, archivo, nombre, "TAR.XZ") { xz(archivo) }

    companion object {
        fun xz(f: File): InputStream = XZCompressorInputStream(Peek.abrirBuffer(f))
    }
}
