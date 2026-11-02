package com.carpe.descompresor.extraer

import com.carpe.descompresor.util.Formato
import java.io.File

class InformeExtraccion {
    val extraidos = ArrayList<String>()
    val fallidos = ArrayList<Pair<String, String>>()
    val omitidos = ArrayList<Pair<String, String>>()
    var cancelado: Boolean = false
    var bytes: Long = 0L
    var destino: String = ""
    var carpetaLocal: File? = null
    var yaExistian: Int = 0
    var permiteReintento: Boolean = true

    fun resumen(): String {
        val sb = StringBuilder()
        if (cancelado) sb.append("Cancelado. ")
        sb.append("${extraidos.size} extraídos, ${fallidos.size} fallidos, ${omitidos.size} omitidos")
        if (yaExistian > 0) sb.append(", $yaExistian ya existían")
        sb.append(" · ${Formato.bytes(bytes)}")
        return sb.toString()
    }

    fun detalle(): String {
        val sb = StringBuilder()
        sb.append(resumen()).append('\n')
        if (destino.isNotEmpty()) sb.append(destino).append('\n')
        if (fallidos.isNotEmpty()) {
            sb.append("\nFallidos:\n")
            for ((r, m) in fallidos.take(50)) sb.append("• ").append(r).append(" — ").append(m).append('\n')
            if (fallidos.size > 50) sb.append("… y ${fallidos.size - 50} más\n")
        }
        if (omitidos.isNotEmpty()) {
            sb.append("\nOmitidos:\n")
            for ((r, m) in omitidos.take(50)) sb.append("• ").append(r).append(" — ").append(m).append('\n')
            if (omitidos.size > 50) sb.append("… y ${omitidos.size - 50} más\n")
        }
        return sb.toString()
    }
}
