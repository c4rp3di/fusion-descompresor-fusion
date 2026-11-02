package com.carpe.descompresor.previsualizacion

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.util.Iconos

class PrevisualizadorPdf : Previsualizador {
    override val tipo: String = "pdf"

    override fun soporta(entrada: Entrada): Boolean = entrada.categoria == Iconos.Categoria.PDF

    override suspend fun previsualizar(ctx: Context, contenedor: Contenedor, entrada: Entrada): Previsualizacion =
        Previsualizacion.Pdf(Temporales.copiar(ctx, contenedor, entrada, 100L * 1024 * 1024))
}
