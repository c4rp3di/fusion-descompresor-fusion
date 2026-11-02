package com.carpe.descompresor.previsualizacion

import android.content.Context
import com.carpe.descompresor.apk.ParserAxml
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.util.Codificacion
import com.carpe.descompresor.util.Iconos
import com.carpe.descompresor.util.PreferenciasApp

class PrevisualizadorTexto : Previsualizador {
    override val tipo: String = "texto"

    override fun soporta(entrada: Entrada): Boolean {
        val c = entrada.categoria
        return c == Iconos.Categoria.TEXTO || c == Iconos.Categoria.CODIGO || c == Iconos.Categoria.DESCONOCIDO
    }

    override suspend fun previsualizar(ctx: Context, contenedor: Contenedor, entrada: Entrada): Previsualizacion {
        val prefs = PreferenciasApp(ctx)
        val max = prefs.entero("prev_texto_kb").coerceAtLeast(1) * 1024
        val bytes = Temporales.leerPrimeros(contenedor, entrada, max)
        val truncado = entrada.tamano < 0L || entrada.tamano > bytes.size || bytes.size >= max

        // XML binario de Android dentro de un APK
        if (ParserAxml.esAxml(bytes) && bytes.size.toLong() >= entrada.tamano && entrada.tamano >= 0L) {
            return try {
                Previsualizacion.Texto(ParserAxml.parsear(bytes).xml, "AXML → XML", false)
            } catch (e: Exception) {
                Previsualizacion.NoSoportada("XML binario de Android no legible: " + (e.message ?: ""))
            }
        }
        if (Codificacion.pareceBinario(bytes)) {
            return PrevisualizadorHex().volcado(bytes, truncado)
        }
        val (texto, cs) = Codificacion.decodificar(bytes, prefs.cadena("codificacion_defecto"))
        return Previsualizacion.Texto(texto, cs, truncado)
    }
}
