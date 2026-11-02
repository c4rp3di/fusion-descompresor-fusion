package com.carpe.descompresor.motor

import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.util.NoClose
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream

/** Lector de un formato de flujo (tar, cpio, ar, 7z, .gz...). */
interface LectorSecuencial : Closeable {
    fun siguiente(): Entrada?
    fun flujo(): InputStream
}

class ContenedorSecuencial(
    nombre: String,
    formato: String,
    archivo: File,
    private val abrir: () -> LectorSecuencial,
    maxEntradas: Int
) : Contenedor(nombre, formato, archivo) {

    override val entradas: List<Entrada>

    init {
        val lista = ArrayList<Entrada>()
        abrir().use { lector ->
            while (true) {
                val e = lector.siguiente() ?: break
                lista.add(e)
                if (lista.size > maxEntradas) {
                    throw IllegalStateException("Demasiadas entradas (más de $maxEntradas; ajustable en Ajustes)")
                }
            }
        }
        entradas = lista
    }

    override fun abrirEntrada(entrada: Entrada): InputStream {
        val lector = abrir()
        try {
            while (true) {
                val e = lector.siguiente() ?: break
                if (!e.esDirectorio && e.ruta == entrada.ruta) {
                    val f = lector.flujo()
                    return object : FilterInputStream(f) {
                        override fun close() {
                            lector.close()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            lector.close()
            throw t
        }
        lector.close()
        throw FileNotFoundException(entrada.ruta)
    }

    override suspend fun recorrer(rutas: Set<String>?, accion: suspend (Entrada, InputStream) -> Unit) {
        abrir().use { lector ->
            while (true) {
                val e = lector.siguiente() ?: break
                if (e.esDirectorio) continue
                if (rutas != null && e.ruta !in rutas) continue
                accion(e, NoClose(lector.flujo()))
            }
        }
    }
}
