package com.carpe.descompresor.previsualizacion

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada

class PrevisualizadorHex : Previsualizador {
    override val tipo: String = "hex"

    override fun soporta(entrada: Entrada): Boolean = true

    override suspend fun previsualizar(ctx: Context, contenedor: Contenedor, entrada: Entrada): Previsualizacion {
        val bytes = Temporales.leerPrimeros(contenedor, entrada, LIMITE)
        return volcado(bytes, entrada.tamano < 0L || entrada.tamano > bytes.size)
    }

    fun volcado(b: ByteArray, truncado: Boolean): Previsualizacion {
        val n = minOf(b.size, LIMITE)
        val sb = StringBuilder()
        var i = 0
        while (i < n) {
            sb.append(String.format("%08X  ", i))
            val txt = StringBuilder()
            for (j in 0 until 16) {
                if (i + j < n) {
                    val v = b[i + j].toInt() and 0xFF
                    sb.append(String.format("%02X ", v))
                    txt.append(if (v in 32..126) v.toChar() else '.')
                } else {
                    sb.append("   ")
                }
            }
            sb.append(" |").append(txt).append("|\n")
            i += 16
        }
        return Previsualizacion.Hex(sb.toString(), truncado || b.size > LIMITE)
    }

    companion object {
        const val LIMITE = 4096
    }
}
