package com.carpe.descompresor.apk

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** zipalign en Kotlin puro: reescribe el ZIP con las entradas sin comprimir alineadas. */
object AlineadorZip {
    private const val SIG_EOCD = 0x06054b50
    private const val SIG_CD = 0x02014b50
    private const val SIG_LH = 0x04034b50

    private class EntradaCd(
        val nombre: String,
        val metodo: Int,
        val flags: Int,
        val crc: Long,
        val csize: Long,
        val usize: Long,
        val offsetLocal: Long,
        val registro: ByteArray
    )

    private class Conta(private val o: OutputStream) : OutputStream() {
        var pos: Long = 0L
        override fun write(b: Int) { o.write(b); pos++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); pos += len.toLong() }
        override fun flush() { o.flush() }
        override fun close() { o.close() }
    }

    private fun leerCd(raf: RandomAccessFile): List<EntradaCd> {
        val largo = raf.length()
        val ventana = minOf(largo, 65557L).toInt()
        val cola = ByteArray(ventana)
        raf.seek(largo - ventana)
        raf.readFully(cola)
        var e = -1
        for (i in ventana - 22 downTo 0) {
            if (cola[i].toInt() == 0x50 && cola[i + 1].toInt() == 0x4B && cola[i + 2].toInt() == 0x05 && cola[i + 3].toInt() == 0x06) {
                e = i
                break
            }
        }
        if (e < 0) throw IOException("No es un ZIP válido (sin EOCD)")
        val bb = ByteBuffer.wrap(cola).order(ByteOrder.LITTLE_ENDIAN)
        val total = bb.getShort(e + 10).toInt() and 0xFFFF
        val cdTam = bb.getInt(e + 12).toLong() and 0xFFFFFFFFL
        val cdOff = bb.getInt(e + 16).toLong() and 0xFFFFFFFFL
        if (total == 0xFFFF || cdTam == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) throw IOException("ZIP64 no soportado por el alineador")
        val cd = ByteArray(cdTam.toInt())
        raf.seek(cdOff)
        raf.readFully(cd)
        val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        val lista = ArrayList<EntradaCd>()
        var p = 0
        for (i in 0 until total) {
            if (p + 46 > cd.size || b.getInt(p) != SIG_CD) throw IOException("Directorio central corrupto")
            val flags = b.getShort(p + 8).toInt() and 0xFFFF
            val metodo = b.getShort(p + 10).toInt() and 0xFFFF
            val crc = b.getInt(p + 16).toLong() and 0xFFFFFFFFL
            val cs = b.getInt(p + 20).toLong() and 0xFFFFFFFFL
            val us = b.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nl = b.getShort(p + 28).toInt() and 0xFFFF
            val el = b.getShort(p + 30).toInt() and 0xFFFF
            val cl = b.getShort(p + 32).toInt() and 0xFFFF
            val off = b.getInt(p + 42).toLong() and 0xFFFFFFFFL
            val nombre = String(cd, p + 46, nl, Charsets.UTF_8)
            val largoReg = 46 + nl + el + cl
            if (p + largoReg > cd.size) throw IOException("Directorio central corrupto")
            lista.add(EntradaCd(nombre, metodo, flags, crc, cs, us, off, cd.copyOfRange(p, p + largoReg)))
            p += largoReg
        }
        return lista
    }

    /** Devuelve los nombres de entradas sin comprimir cuyo dato no empieza en offset múltiplo de [alineacion]. */
    fun verificar(archivo: File, alineacion: Int = 4): List<String> {
        val malas = ArrayList<String>()
        RandomAccessFile(archivo, "r").use { raf ->
            for (e in leerCd(raf)) {
                if (e.metodo != 0) continue
                raf.seek(e.offsetLocal)
                val lh = ByteArray(30)
                raf.readFully(lh)
                val b = ByteBuffer.wrap(lh).order(ByteOrder.LITTLE_ENDIAN)
                if (b.getInt(0) != SIG_LH) throw IOException("Cabecera local corrupta: ${e.nombre}")
                val nl = b.getShort(26).toInt() and 0xFFFF
                val el = b.getShort(28).toInt() and 0xFFFF
                val datos = e.offsetLocal + 30 + nl + el
                if (datos % alineacion != 0L) malas.add(e.nombre)
            }
        }
        return malas
    }

    fun alinear(origen: File, destino: File, alineacion: Int = 4) {
        RandomAccessFile(origen, "r").use { raf ->
            val entradas = leerCd(raf)
            val out = Conta(BufferedOutputStream(FileOutputStream(destino), 128 * 1024))
            try {
                val nuevos = LongArray(entradas.size)
                val copia = ByteArray(64 * 1024)
                for ((i, e) in entradas.withIndex()) {
                    raf.seek(e.offsetLocal)
                    val lh = ByteArray(30)
                    raf.readFully(lh)
                    val lb = ByteBuffer.wrap(lh).order(ByteOrder.LITTLE_ENDIAN)
                    if (lb.getInt(0) != SIG_LH) throw IOException("Cabecera local corrupta: ${e.nombre}")
                    val nl = lb.getShort(26).toInt() and 0xFFFF
                    val el = lb.getShort(28).toInt() and 0xFFFF
                    val nombreBytes = ByteArray(nl)
                    raf.readFully(nombreBytes)
                    val inicioDatos = e.offsetLocal + 30 + nl + el

                    nuevos[i] = out.pos
                    var pad = 0
                    if (e.metodo == 0) {
                        val resto = ((out.pos + 30 + nl) % alineacion).toInt()
                        if (resto != 0) {
                            val p = alineacion - resto
                            pad = if (p < 4) p + alineacion else p
                        }
                    }
                    val extra = ByteArray(pad)
                    if (pad >= 4) {
                        extra[0] = 0x35
                        extra[1] = 0xD9.toByte()
                        extra[2] = ((pad - 4) and 0xFF).toByte()
                        extra[3] = (((pad - 4) shr 8) and 0xFF).toByte()
                    }
                    val nh = ByteArray(30)
                    val nb = ByteBuffer.wrap(nh).order(ByteOrder.LITTLE_ENDIAN)
                    nb.putInt(0, SIG_LH)
                    nb.putShort(4, lb.getShort(4))
                    nb.putShort(6, (e.flags and 0x0008.inv()).toShort())
                    nb.putShort(8, e.metodo.toShort())
                    nb.putShort(10, lb.getShort(10))
                    nb.putShort(12, lb.getShort(12))
                    nb.putInt(14, e.crc.toInt())
                    nb.putInt(18, e.csize.toInt())
                    nb.putInt(22, e.usize.toInt())
                    nb.putShort(26, nl.toShort())
                    nb.putShort(28, pad.toShort())
                    out.write(nh)
                    out.write(nombreBytes)
                    out.write(extra)

                    raf.seek(inicioDatos)
                    var restante = e.csize
                    while (restante > 0) {
                        val n = raf.read(copia, 0, minOf(copia.size.toLong(), restante).toInt())
                        if (n < 0) throw IOException("Fin de archivo inesperado en ${e.nombre}")
                        out.write(copia, 0, n)
                        restante -= n.toLong()
                    }
                }
                val inicioCd = out.pos
                for ((i, e) in entradas.withIndex()) {
                    val reg = e.registro.copyOf()
                    val rb = ByteBuffer.wrap(reg).order(ByteOrder.LITTLE_ENDIAN)
                    rb.putShort(8, (e.flags and 0x0008.inv()).toShort())
                    rb.putInt(42, nuevos[i].toInt())
                    out.write(reg)
                }
                val tamCd = out.pos - inicioCd
                if (out.pos > 0xFFFFFFFFL || entradas.size > 0xFFFE) throw IOException("El resultado excede los límites de ZIP sin ZIP64")
                val eocd = ByteArray(22)
                val eb = ByteBuffer.wrap(eocd).order(ByteOrder.LITTLE_ENDIAN)
                eb.putInt(0, SIG_EOCD)
                eb.putShort(8, entradas.size.toShort())
                eb.putShort(10, entradas.size.toShort())
                eb.putInt(12, tamCd.toInt())
                eb.putInt(16, inicioCd.toInt())
                out.write(eocd)
                out.flush()
            } finally {
                out.close()
            }
        }
    }
}
