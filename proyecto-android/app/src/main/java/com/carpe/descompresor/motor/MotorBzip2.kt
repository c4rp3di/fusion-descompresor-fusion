package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import java.io.File

class MotorBzip2 : MotorContenedor {
    override val id: String = "bz2"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.BZIP2

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        if (Peek.pareceTar { MotorTarBz2.bz(archivo) }) {
            return contenedorTar(ctx, archivo, nombre, "TAR.BZ2") { MotorTarBz2.bz(archivo) }
        }
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        val salida = Peek.nombreSinExtension(nombre, listOf(".bz2", ".bz"))
        return ContenedorSecuencial(nombre, "BZIP2", archivo, {
            LectorUnico(MotorTarBz2.bz(archivo), salida, "bzip2")
        }, lim.maxEntradas)
    }
}
