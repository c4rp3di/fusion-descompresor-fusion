package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import java.io.File

class MotorCpio : MotorContenedor {
    override val id: String = "cpio"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.CPIO

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        return ContenedorSecuencial(nombre, "CPIO", archivo, { LectorCpio(CpioArchiveInputStream(Peek.abrirBuffer(archivo))) }, lim.maxEntradas)
    }
}
