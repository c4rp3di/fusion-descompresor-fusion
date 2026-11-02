package com.carpe.descompresor.destino

import android.content.Context

object CatalogoDestinos {
    fun crear(id: String, detalle: String = ""): EstrategiaDestino = when (id) {
        "almacenamiento" -> AlmacenamientoDestino()
        "interno" -> InternoDestino(detalle)
        else -> SafDestino()
    }

    fun todos(): List<EstrategiaDestino> = listOf(SafDestino(), AlmacenamientoDestino(), InternoDestino())

    /** Mejor destino por defecto: SAF si hay carpeta elegida, acceso total si está concedido, interno como último recurso. */
    fun mejorPorDefecto(ctx: Context, preferido: String): String {
        val p = crear(preferido)
        if (p.disponible(ctx)) return preferido
        return "interno"
    }
}
