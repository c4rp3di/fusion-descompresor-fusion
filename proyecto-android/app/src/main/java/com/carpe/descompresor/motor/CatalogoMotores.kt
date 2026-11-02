package com.carpe.descompresor.motor

import android.content.Context
import android.net.Uri
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.CopiaCancelada
import com.carpe.descompresor.util.CopiaLocal
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import com.carpe.descompresor.util.Volumenes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object CatalogoMotores {
    val motores: List<MotorContenedor> = listOf(
        MotorApk(), MotorZip(),
        MotorTarGz(), MotorTarBz2(), MotorTarXz(), MotorTar(),
        MotorGzip(), MotorBzip2(), MotorXz(),
        MotorSieteZ(), MotorRar(), MotorCpio(), MotorAr()
    )

    /** Copia la Uri a caché (con progreso y cancelación) y abre el contenedor. */
    suspend fun abrir(
        ctx: Context,
        uri: Uri,
        maxBytes: Long,
        progreso: ((Long, Long) -> Unit)?,
        cancelado: (() -> Boolean)?
    ): Contenedor = withContext(Dispatchers.IO) {
        val copia = CopiaLocal.copiar(ctx, uri, maxBytes, progreso, cancelado)
        try {
            if (cancelado != null && cancelado()) throw CopiaCancelada()
            abrirArchivo(ctx, copia.archivo, copia.nombre)
        } catch (t: Throwable) {
            copia.archivo.delete()
            throw t
        }
    }

    /** Abre un archivo local ya copiado (quien llama borra el archivo si esto falla). */
    suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        val cab = CopiaLocal.cabecera(archivo, 512)
        val motor = motores.firstOrNull { it.puedeAbrir(cab, nombre) }
            ?: throw IOException("Formato no reconocido: $nombre")
        return motor.abrirArchivo(ctx, archivo, nombre)
    }

    /** Abre un archivo dividido en varios volúmenes (todas las partes elegidas a la vez). */
    suspend fun abrirVolumenes(
        ctx: Context,
        uris: List<Uri>,
        maxBytes: Long,
        progreso: ((Long, Long) -> Unit)?,
        cancelado: (() -> Boolean)?
    ): Contenedor = withContext(Dispatchers.IO) {
        val partes = uris.map { Volumenes.Parte(it, CopiaLocal.nombreDe(ctx, it)) }
        val plan = Volumenes.planificar(partes)
        val res = Volumenes.copiar(ctx, plan, maxBytes, progreso, cancelado)
        try {
            if (cancelado != null && cancelado()) throw CopiaCancelada()
            val lim = Seguridad.limites(PreferenciasApp(ctx))
            when (plan.tipo) {
                Volumenes.Tipo.NUMERICO -> abrirArchivo(ctx, res.archivo, plan.nombreBase)
                Volumenes.Tipo.ZIP_DIVIDIDO -> ContenedorZip(res.nombre, "ZIP (dividido)", res.archivo, lim.maxEntradas, true)
                Volumenes.Tipo.RAR -> ContenedorRar(res.nombre, res.archivo, File(ctx.cacheDir, "rar_tmp"), lim.maxEntradas)
            }
        } catch (t: Throwable) {
            CopiaLocal.borrar(res.archivo)
            throw t
        }
    }
}
