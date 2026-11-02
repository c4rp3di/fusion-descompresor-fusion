package com.carpe.descompresor.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Archivos recibidos de otras apps (VIEW / SEND). Las Uri content:// traen un permiso temporal,
 * así que se copian una vez a cache/inbox y se trabaja siempre sobre la copia.
 */
object ArchivoInbox {
    private const val HORAS_24 = 24L * 60L * 60L * 1000L

    fun limiteBytes(ctx: Context): Long =
        PreferenciasApp(ctx).entero("max_recibido_mb").toLong() * 1024L * 1024L

    fun recibir(
        ctx: Context,
        uri: Uri,
        progreso: ((Long, Long) -> Unit)?,
        cancelado: (() -> Boolean)?
    ): CopiaLocal.Resultado = CopiaLocal.copiar(ctx, uri, limiteBytes(ctx), progreso, cancelado)

    /** Al iniciar la app sin contenedor abierto: borra todo resto de sesiones anteriores. */
    fun barrerTodo(ctx: Context) {
        CopiaLocal.limpiarAbiertos(ctx)
    }

    /** Con la app en uso: borra solo lo que lleva más de 24 h. */
    fun barrerAntiguos(ctx: Context) {
        val limite = System.currentTimeMillis() - HORAS_24
        val d: File = CopiaLocal.carpetaAbiertos(ctx)
        d.listFiles()?.forEach { if (it.lastModified() < limite) it.deleteRecursively() }
    }
}
