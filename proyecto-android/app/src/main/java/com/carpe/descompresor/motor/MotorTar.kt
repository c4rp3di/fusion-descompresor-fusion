package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

internal fun contenedorTar(ctx: Context, archivo: File, nombre: String, formato: String, flujo: () -> InputStream): Contenedor {
    val lim = Seguridad.limites(PreferenciasApp(ctx))
    return ContenedorSecuencial(nombre, formato, archivo, { LectorTar(TarArchiveInputStream(BufferedInputStream(flujo(), 64 * 1024))) }, lim.maxEntradas)
}

class MotorTar : MotorContenedor {
    override val id: String = "tar"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.TAR

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor =
        contenedorTar(ctx, archivo, nombre, "TAR") { Peek.abrirBuffer(archivo) }
}
