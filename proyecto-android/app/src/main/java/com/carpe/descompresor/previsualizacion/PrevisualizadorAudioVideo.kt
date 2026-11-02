package com.carpe.descompresor.previsualizacion

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.util.Iconos

class PrevisualizadorAudioVideo : Previsualizador {
    override val tipo: String = "medio"

    override fun soporta(entrada: Entrada): Boolean =
        entrada.categoria == Iconos.Categoria.AUDIO || entrada.categoria == Iconos.Categoria.VIDEO

    override suspend fun previsualizar(ctx: Context, contenedor: Contenedor, entrada: Entrada): Previsualizacion =
        Previsualizacion.Medio(Temporales.copiar(ctx, contenedor, entrada, 200L * 1024 * 1024))
}
