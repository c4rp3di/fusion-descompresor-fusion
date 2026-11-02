package com.carpe.descompresor.motor

import android.content.Context
import android.net.Uri
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.CopiaLocal
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import java.io.File

interface MotorContenedor {
    val id: String
    fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean
    suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor

    suspend fun abrir(ctx: Context, uri: Uri): Contenedor {
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        val copia = CopiaLocal.copiar(ctx, uri, lim.maxContenedorBytes)
        return abrirArchivo(ctx, copia.archivo, copia.nombre)
    }
}
