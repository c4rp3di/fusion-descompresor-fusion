package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import java.io.File

class MotorApk : MotorContenedor {
    override val id: String = "apk"

    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.APK

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        return ContenedorApk(nombre, archivo, lim.maxEntradas)
    }
}
