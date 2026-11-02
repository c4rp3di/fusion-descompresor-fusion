package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File

class MotorGzip : MotorContenedor {
    override val id: String = "gz"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.GZIP

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        if (Peek.pareceTar { MotorTarGz.gz(archivo) }) {
            return contenedorTar(ctx, archivo, nombre, "TAR.GZ") { MotorTarGz.gz(archivo) }
        }
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        var nombreInterno: String? = null
        try {
            GzipCompressorInputStream(Peek.abrirBuffer(archivo)).use { g ->
                nombreInterno = g.metaData.filename
            }
        } catch (e: Exception) {
            nombreInterno = null
        }
        val salida = nombreInterno?.takeIf { it.isNotBlank() } ?: Peek.nombreSinExtension(nombre, listOf(".gz", ".gzip", ".z"))
        return ContenedorSecuencial(nombre, "GZIP", archivo, {
            LectorUnico(GzipCompressorInputStream(Peek.abrirBuffer(archivo)), salida, "gzip")
        }, lim.maxEntradas)
    }
}
