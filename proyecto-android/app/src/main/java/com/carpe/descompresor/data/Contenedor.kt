package com.carpe.descompresor.data

import java.io.Closeable
import java.io.File
import java.io.InputStream

abstract class Contenedor(
    val nombre: String,
    val formato: String,
    val archivo: File
) : Closeable {

    abstract val entradas: List<Entrada>

    open val esApk: Boolean
        get() = false

    val tamanoArchivo: Long
        get() = archivo.length()

    /** Abre el contenido de una entrada. El llamante debe cerrar el flujo. */
    abstract fun abrirEntrada(entrada: Entrada): InputStream

    /** Recorre las entradas (archivos) pedidas. Las implementaciones secuenciales lo hacen en una sola pasada. */
    open suspend fun recorrer(rutas: Set<String>?, accion: suspend (Entrada, InputStream) -> Unit) {
        for (e in entradas) {
            if (e.esDirectorio) continue
            if (rutas != null && e.ruta !in rutas) continue
            abrirEntrada(e).use { accion(e, it) }
        }
    }

    override fun close() {}
}
