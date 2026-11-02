package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File

class MotorSieteZ : MotorContenedor {
    override val id: String = "7z"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.SIETE_Z

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        return ContenedorSecuencial(nombre, "7z", archivo, { LectorSevenZ(SevenZFile(archivo)) }, lim.maxEntradas)
    }
}
