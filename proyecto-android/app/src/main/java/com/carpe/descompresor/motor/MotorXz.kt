package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import java.io.File

class MotorXz : MotorContenedor {
    override val id: String = "xz"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.XZ

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        if (Peek.pareceTar { MotorTarXz.xz(archivo) }) {
            return contenedorTar(ctx, archivo, nombre, "TAR.XZ") { MotorTarXz.xz(archivo) }
        }
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        val salida = Peek.nombreSinExtension(nombre, listOf(".xz", ".lzma"))
        return ContenedorSecuencial(nombre, "XZ", archivo, {
            LectorUnico(MotorTarXz.xz(archivo), salida, "xz")
        }, lim.maxEntradas)
    }
}
