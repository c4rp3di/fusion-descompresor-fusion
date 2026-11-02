package com.carpe.descompresor.previsualizacion

import android.content.Context
import android.graphics.Bitmap
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.util.Flujos
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed class Previsualizacion {
    class Texto(val texto: String, val codificacion: String, val truncado: Boolean) : Previsualizacion()
    class Imagen(val bitmap: Bitmap, val info: String) : Previsualizacion()
    class Hex(val volcado: String, val truncado: Boolean) : Previsualizacion()
    class Html(val html: String) : Previsualizacion()
    class Pdf(val archivo: File) : Previsualizacion()
    class Medio(val archivo: File) : Previsualizacion()
    class NoSoportada(val motivo: String) : Previsualizacion()
}

interface Previsualizador {
    val tipo: String
    fun soporta(entrada: Entrada): Boolean
    suspend fun previsualizar(ctx: Context, contenedor: Contenedor, entrada: Entrada): Previsualizacion
}

object Temporales {
    fun copiar(ctx: Context, contenedor: Contenedor, entrada: Entrada, maxBytes: Long): File {
        if (entrada.tamano > maxBytes) throw IOException("La entrada supera el límite de vista previa")
        val d = File(ctx.cacheDir, "preview")
        d.mkdirs()
        val ext = entrada.nombre.substringAfterLast('.', "bin").replace(Regex("[^A-Za-z0-9]"), "")
        val f = File(d, "p_" + System.currentTimeMillis() + "." + ext)
        contenedor.abrirEntrada(entrada).use { i ->
            FileOutputStream(f).use { o ->
                var total = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) {
                        f.delete()
                        throw IOException("La entrada supera el límite de vista previa")
                    }
                    o.write(buf, 0, n)
                }
            }
        }
        return f
    }

    fun limpiar(ctx: Context) {
        File(ctx.cacheDir, "preview").listFiles()?.forEach { it.delete() }
    }

    fun leerPrimeros(contenedor: Contenedor, entrada: Entrada, max: Int): ByteArray {
        return contenedor.abrirEntrada(entrada).use { Flujos.leerHasta(it, max) }
    }
}
