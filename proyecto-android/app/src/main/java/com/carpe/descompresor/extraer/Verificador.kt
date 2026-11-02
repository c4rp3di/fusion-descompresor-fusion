package com.carpe.descompresor.extraer

import com.carpe.descompresor.data.Contenedor
import java.util.zip.CRC32

class InformeVerificacion {
    var correctos: Int = 0
    var omitidos: Int = 0
    val fallos = ArrayList<Pair<String, String>>()
    var cancelado: Boolean = false
    var bytes: Long = 0L
    var total: Int = 0

    fun resumen(): String {
        val sb = StringBuilder()
        if (cancelado) sb.append("Cancelado. ")
        sb.append("$correctos correctos, ${fallos.size} con errores")
        if (omitidos > 0) sb.append(", $omitidos sin verificar (cifrados)")
        sb.append(" de $total archivos.")
        return sb.toString()
    }

    fun detalle(): String {
        val sb = StringBuilder(resumen())
        sb.append('\n')
        if (fallos.isNotEmpty()) {
            sb.append("\nErrores:\n")
            for ((r, m) in fallos.take(60)) sb.append("• ").append(r).append(" — ").append(m).append('\n')
            if (fallos.size > 60) sb.append("… y ${fallos.size - 60} más\n")
        } else if (!cancelado) {
            sb.append("\nNo se encontraron errores de lectura, tamaño ni CRC.")
        }
        return sb.toString()
    }
}

/** Lee todo el contenedor sin escribir nada y comprueba CRC y tamaños. */
object Verificador {
    suspend fun verificar(
        c: Contenedor,
        alProgreso: (hechos: Int, total: Int, bytes: Long, actual: String) -> Unit,
        cancelado: () -> Boolean
    ): InformeVerificacion {
        val inf = InformeVerificacion()
        inf.total = c.entradas.count { !it.esDirectorio }
        var hechos = 0
        var ultimo = 0L
        try {
            c.recorrer(null) { e, flujo ->
                if (cancelado()) throw CancelacionExtraccion()
                if (e.cifrada) {
                    inf.omitidos++
                    hechos++
                    alProgreso(hechos, inf.total, inf.bytes, e.ruta)
                    return@recorrer
                }
                try {
                    val crc = CRC32()
                    var n = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = flujo.read(buf)
                        if (r < 0) break
                        crc.update(buf, 0, r)
                        n += r.toLong()
                        inf.bytes += r.toLong()
                        if (cancelado()) throw CancelacionExtraccion()
                        val ahora = System.currentTimeMillis()
                        if (ahora - ultimo > 150L) {
                            ultimo = ahora
                            alProgreso(hechos, inf.total, inf.bytes, e.ruta)
                        }
                    }
                    if (e.tamano >= 0L && n != e.tamano) {
                        inf.fallos.add(Pair(e.ruta, "tamaño distinto del declarado ($n de ${e.tamano} bytes)"))
                    } else if (e.crc >= 0L && crc.value != e.crc) {
                        inf.fallos.add(Pair(e.ruta, "CRC inválido"))
                    } else {
                        inf.correctos++
                    }
                } catch (x: CancelacionExtraccion) {
                    throw x
                } catch (x: kotlinx.coroutines.CancellationException) {
                    throw x
                } catch (x: Exception) {
                    inf.fallos.add(Pair(e.ruta, x.message ?: x.javaClass.simpleName))
                }
                hechos++
                alProgreso(hechos, inf.total, inf.bytes, e.ruta)
            }
        } catch (x: CancelacionExtraccion) {
            inf.cancelado = true
        } catch (x: kotlinx.coroutines.CancellationException) {
            throw x
        } catch (x: Exception) {
            inf.fallos.add(Pair("(contenedor)", "Lectura interrumpida: " + (x.message ?: x.javaClass.simpleName)))
        }
        return inf
    }
}
