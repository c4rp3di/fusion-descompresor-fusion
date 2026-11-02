package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

class MotorTarGz : MotorContenedor {
    override val id: String = "tar.gz"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.TAR_GZ

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor =
        contenedorTar(ctx, archivo, nombre, "TAR.GZ") { gz(archivo) }

    companion object {
        fun gz(f: File): InputStream = GZIPInputStream(Peek.abrirBuffer(f), 64 * 1024)
    }
}
