package com.carpe.descompresor.previsualizacion

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.util.Codificacion
import com.carpe.descompresor.util.PreferenciasApp

class PrevisualizadorHtml : Previsualizador {
    override val tipo: String = "html"

    override fun soporta(entrada: Entrada): Boolean {
        val e = com.carpe.descompresor.util.Iconos.extension(entrada.nombre)
        return e == "html" || e == "htm"
    }

    override suspend fun previsualizar(ctx: Context, contenedor: Contenedor, entrada: Entrada): Previsualizacion {
        val prefs = PreferenciasApp(ctx)
        val bytes = Temporales.leerPrimeros(contenedor, entrada, prefs.entero("prev_texto_kb").coerceAtLeast(1) * 1024)
        val (t, _) = Codificacion.decodificar(bytes, prefs.cadena("codificacion_defecto"))
        return Previsualizacion.Html(t)
    }
}
