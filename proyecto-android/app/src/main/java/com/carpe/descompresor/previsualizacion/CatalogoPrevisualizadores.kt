package com.carpe.descompresor.previsualizacion

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada

object CatalogoPrevisualizadores {
    private val lista: List<Previsualizador> = listOf(
        PrevisualizadorImagen(),
        PrevisualizadorPdf(),
        PrevisualizadorAudioVideo(),
        PrevisualizadorHtml(),
        PrevisualizadorTexto(),
        PrevisualizadorHex()
    )

    suspend fun generar(ctx: Context, contenedor: Contenedor, entrada: Entrada): Previsualizacion {
        val ext = com.carpe.descompresor.util.Iconos.extension(entrada.nombre)
        if (ext == "arsc" && contenedor.esApk) {
            return Previsualizacion.NoSoportada("resources.arsc es la tabla de recursos binaria de Android: no se previsualiza como texto.")
        }
        if (entrada.cifrada) return Previsualizacion.NoSoportada("Entrada cifrada o con método no soportado.")
        for (p in lista) {
            if (p.soporta(entrada)) return p.previsualizar(ctx, contenedor, entrada)
        }
        return Previsualizacion.NoSoportada("Tipo no previsualizable.")
    }
}
