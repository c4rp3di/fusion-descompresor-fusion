package com.carpe.descompresor.util

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Cuenta bytes leídos y corta si se supera el límite (protege de cabeceras que mienten). */
class ContadorInput(
    input: InputStream,
    private val limite: Long,
    private val alBytes: ((Long) -> Unit)?
) : FilterInputStream(input) {
    var total: Long = 0L
        private set

    private fun sumar(n: Int) {
        if (n <= 0) return
        total += n.toLong()
        if (limite >= 0 && total > limite) throw IOException("Se supera el tamaño máximo por entrada")
        alBytes?.invoke(n.toLong())
    }

    override fun read(): Int {
        val r = super.read()
        if (r >= 0) sumar(1)
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = super.read(b, off, len)
        if (r > 0) sumar(r)
        return r
    }
}

class NoClose(input: InputStream) : FilterInputStream(input) {
    override fun close() { /* no cierra el flujo subyacente */ }
}

object Flujos {
    fun copiar(i: InputStream, o: OutputStream, buffer: Int): Long {
        val buf = ByteArray(buffer.coerceAtLeast(4096))
        var total = 0L
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
            total += n.toLong()
        }
        return total
    }

    fun leerHasta(i: InputStream, maximo: Int): ByteArray {
        val salida = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var restante = maximo
        while (restante > 0) {
            val n = i.read(buf, 0, minOf(buf.size, restante))
            if (n < 0) break
            salida.write(buf, 0, n)
            restante -= n
        }
        return salida.toByteArray()
    }
}
