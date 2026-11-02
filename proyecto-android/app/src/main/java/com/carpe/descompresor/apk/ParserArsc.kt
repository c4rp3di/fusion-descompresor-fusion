package com.carpe.descompresor.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

class ValorArsc(val densidad: Int, val sdk: Int, val tipo: Int, val dato: Int)

/**
 * Lector mínimo de resources.arsc: resuelve un ID de recurso a sus valores por configuración
 * (por ejemplo, las rutas de archivo de un icono en cada densidad) y permite sustituir una ruta
 * de la tabla de cadenas por otra igual o más corta, sin reconstruir el archivo.
 */
class TablaArsc private constructor(private val datos: ByteArray) {
    private val bb: ByteBuffer = ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN)

    var cadenas: Array<String> = emptyArray()
        private set

    private var poolOffsetsPos = 0
    private var poolStringsPos = 0
    private var poolUtf8 = false

    private class Trozo(val paquete: Int, val tipoId: Int, val offset: Int)

    private val trozos = ArrayList<Trozo>()

    private fun u16(o: Int): Int = bb.getShort(o).toInt() and 0xFFFF

    init {
        if (datos.size < 12 || u16(0) != 0x0002) throw IllegalArgumentException("No es un resources.arsc válido")
        val declarado = bb.getInt(4)
        val total = if (declarado in 12..datos.size) declarado else datos.size
        var pos = u16(2)
        var poolLeido = false
        while (pos + 8 <= total) {
            val t = u16(pos)
            val sz = bb.getInt(pos + 4)
            if (sz < 8 || pos + sz > datos.size) break
            if (t == 0x0001 && !poolLeido) {
                cadenas = leerPool(pos)
                poolLeido = true
            } else if (t == 0x0200) {
                leerPaquete(pos, sz)
            }
            pos += sz
        }
        if (!poolLeido) throw IllegalArgumentException("resources.arsc sin tabla de cadenas")
    }

    private fun leerPaquete(pos: Int, sz: Int) {
        val id = bb.getInt(pos + 8)
        var p = pos + u16(pos + 2)
        val fin = pos + sz
        while (p + 8 <= fin) {
            val t = u16(p)
            val s = bb.getInt(p + 4)
            if (s < 8 || p + s > fin) break
            if (t == 0x0201) {
                trozos.add(Trozo(id, datos[p + 8].toInt() and 0xFF, p))
            }
            p += s
        }
    }

    private fun leerPool(pos: Int): Array<String> {
        val hs = u16(pos + 2)
        val n = bb.getInt(pos + 8)
        val flags = bb.getInt(pos + 16)
        val inicio = bb.getInt(pos + 20)
        poolUtf8 = (flags and 0x100) != 0
        poolOffsetsPos = pos + hs
        poolStringsPos = pos + inicio
        if (n < 0 || n > 2_000_000) throw IllegalArgumentException("Tabla de cadenas corrupta")
        return Array(n) { i ->
            try {
                val off = poolStringsPos + bb.getInt(poolOffsetsPos + i * 4)
                if (poolUtf8) leerUtf8(off) else leerUtf16(off)
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun leerUtf8(off: Int): String {
        var p = off
        val l1 = datos[p].toInt() and 0xFF
        p += if ((l1 and 0x80) != 0) 2 else 1
        var bl = datos[p].toInt() and 0xFF
        p++
        if ((bl and 0x80) != 0) {
            bl = ((bl and 0x7F) shl 8) or (datos[p].toInt() and 0xFF)
            p++
        }
        return String(datos, p, bl, Charsets.UTF_8)
    }

    private fun leerUtf16(off: Int): String {
        var p = off
        var len = u16(p)
        p += 2
        if ((len and 0x8000) != 0) {
            len = ((len and 0x7FFF) shl 16) or u16(p)
            p += 2
        }
        return String(datos, p, len * 2, Charsets.UTF_16LE)
    }

    /** Todos los valores (uno por configuración) del recurso [id]. Solo entradas simples. */
    fun resolver(id: Int): List<ValorArsc> {
        val paquete = (id ushr 24) and 0xFF
        val tipo = (id ushr 16) and 0xFF
        val entrada = id and 0xFFFF
        val salida = ArrayList<ValorArsc>()
        for (t in trozos) {
            if (t.paquete != paquete || t.tipoId != tipo) continue
            val o = t.offset
            val hs = u16(o + 2)
            val flags = datos[o + 9].toInt() and 0xFF
            val cuenta = bb.getInt(o + 12)
            val inicioEntradas = bb.getInt(o + 16)
            val cfg = o + 20
            val tamCfg = bb.getInt(cfg)
            val densidad = if (tamCfg >= 16) u16(cfg + 14) else 0
            val sdk = if (tamCfg >= 28) u16(cfg + 24) else 0
            val tabla = o + hs
            var offEntrada = -1
            if ((flags and 0x01) != 0) {
                // formato disperso: pares (índice u16, offset/4 u16)
                for (i in 0 until cuenta) {
                    if (u16(tabla + i * 4) == entrada) {
                        offEntrada = u16(tabla + i * 4 + 2) * 4
                        break
                    }
                }
            } else if (entrada < cuenta) {
                val v = bb.getInt(tabla + entrada * 4)
                if (v != -1) offEntrada = v
            }
            if (offEntrada < 0) continue
            val e = o + inicioEntradas + offEntrada
            if (e + 16 > datos.size) continue
            val eflags = u16(e + 2)
            if ((eflags and 0x0001) != 0 || (eflags and 0x0008) != 0) continue   // complejo o compacto: no soportado
            val tipoDato = datos[e + 8 + 3].toInt() and 0xFF
            val dato = bb.getInt(e + 8 + 4)
            salida.add(ValorArsc(densidad, sdk, tipoDato, dato))
        }
        return salida
    }

    /**
     * Devuelve una copia de [origen] con la cadena [indice] sustituida por [nueva], o null si no cabe
     * (la nueva debe ocupar igual o menos que la original). La geometría de la tabla no cambia.
     */
    fun parchear(origen: ByteArray, indice: Int, nueva: String): ByteArray? {
        if (indice < 0 || indice >= cadenas.size) return null
        val b = ByteBuffer.wrap(origen).order(ByteOrder.LITTLE_ENDIAN)
        val abs = poolStringsPos + b.getInt(poolOffsetsPos + indice * 4)
        val copia = origen.copyOf()
        if (poolUtf8) {
            var p = abs
            val l1 = origen[p].toInt() and 0xFF
            p += if ((l1 and 0x80) != 0) 2 else 1
            var bl = origen[p].toInt() and 0xFF
            var pl = 1
            if ((bl and 0x80) != 0) {
                bl = ((bl and 0x7F) shl 8) or (origen[p + 1].toInt() and 0xFF)
                pl = 2
            }
            val finOriginal = p + pl + bl + 1
            val bytes = nueva.toByteArray(Charsets.UTF_8)
            if (bytes.size > 127 || nueva.length > 127) return null
            if (abs + 2 + bytes.size + 1 > finOriginal) return null
            var q = abs
            copia[q++] = nueva.length.toByte()
            copia[q++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, copia, q, bytes.size)
            q += bytes.size
            copia[q++] = 0
            while (q < finOriginal) copia[q++] = 0
        } else {
            var len = b.getShort(abs).toInt() and 0xFFFF
            var p = abs + 2
            if ((len and 0x8000) != 0) {
                len = ((len and 0x7FFF) shl 16) or (b.getShort(p).toInt() and 0xFFFF)
                p += 2
            }
            val finOriginal = p + len * 2 + 2
            if (nueva.length > 0x7FFF) return null
            if (abs + 2 + nueva.length * 2 + 2 > finOriginal) return null
            var q = abs
            copia[q++] = (nueva.length and 0xFF).toByte()
            copia[q++] = ((nueva.length shr 8) and 0xFF).toByte()
            for (ch in nueva) {
                copia[q++] = (ch.code and 0xFF).toByte()
                copia[q++] = ((ch.code shr 8) and 0xFF).toByte()
            }
            copia[q++] = 0
            copia[q++] = 0
            while (q < finOriginal) copia[q++] = 0
        }
        return copia
    }

    companion object {
        fun leer(datos: ByteArray): TablaArsc = TablaArsc(datos)
    }
}
