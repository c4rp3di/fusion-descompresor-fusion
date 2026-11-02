package com.carpe.descompresor.previsualizacion

import android.content.Context
import android.graphics.BitmapFactory
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.util.Iconos
import com.carpe.descompresor.util.PreferenciasApp

class PrevisualizadorImagen : Previsualizador {
    override val tipo: String = "imagen"

    override fun soporta(entrada: Entrada): Boolean =
        entrada.categoria == Iconos.Categoria.IMAGEN && Iconos.extension(entrada.nombre) != "svg"

    override suspend fun previsualizar(ctx: Context, contenedor: Contenedor, entrada: Entrada): Previsualizacion {
        val maxMp = PreferenciasApp(ctx).entero("prev_imagen_mp").coerceAtLeast(1)
        val tope = 32 * 1024 * 1024
        if (entrada.tamano > tope) return Previsualizacion.NoSoportada("Imagen demasiado grande para previsualizar")
        val bytes = Temporales.leerPrimeros(contenedor, entrada, tope)
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return Previsualizacion.NoSoportada("Formato de imagen no reconocido")
        val maxPx = maxMp.toLong() * 1_000_000L
        var muestra = 1
        while ((o.outWidth.toLong() / muestra) * (o.outHeight.toLong() / muestra) > maxPx) muestra *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = muestra
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
            ?: return Previsualizacion.NoSoportada("No se pudo decodificar la imagen")
        return Previsualizacion.Imagen(bmp, "${o.outWidth}×${o.outHeight}" + if (muestra > 1) " (reducida 1/$muestra)" else "")
    }
}
