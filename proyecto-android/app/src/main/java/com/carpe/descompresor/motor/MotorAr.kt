package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import java.io.File

class MotorAr : MotorContenedor {
    override val id: String = "ar"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.AR

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        return ContenedorSecuencial(nombre, "AR", archivo, { LectorAr(ArArchiveInputStream(Peek.abrirBuffer(archivo))) }, lim.maxEntradas)
    }
}
