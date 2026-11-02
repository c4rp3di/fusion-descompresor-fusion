package com.carpe.descompresor.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

class NodoAxml(val tag: String, val atributos: LinkedHashMap<String, String>) {
    val hijos = ArrayList<NodoAxml>()
    var texto: String? = null
}

class ResultadoAxml(val raiz: NodoAxml?, val xml: String)

/** Se invoca por cada atributo leído: sirve al escritor para conservar IDs de recurso y tipos originales. */
fun interface ObservadorAtributo {
    fun alAtributo(uri: String, nombre: String, idRecurso: Int, texto: String, tipo: Int, dato: Int)
}

/** Parser propio del XML binario de Android (AXML). Java/Kotlin puro, sin librerías externas. */
object ParserAxml {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_START_NS = 0x0100
    private const val RES_XML_END_NS = 0x0101
    private const val RES_XML_START_EL = 0x0102
    private const val RES_XML_END_EL = 0x0103
    private const val RES_XML_CDATA = 0x0104
    private const val RES_XML_RESMAP = 0x0180

    private fun u16(b: ByteBuffer, off: Int): Int = b.getShort(off).toInt() and 0xFFFF

    private fun cad(c: Array<String>, i: Int): String = if (i >= 0 && i < c.size) c[i] else ""

    fun esAxml(datos: ByteArray): Boolean {
        if (datos.size < 8) return false
        return (datos[0].toInt() == 0x03 && datos[1].toInt() == 0x00 && datos[2].toInt() == 0x08 && datos[3].toInt() == 0x00)
    }

    fun parsear(datos: ByteArray, observador: ObservadorAtributo? = null): ResultadoAxml {
        if (!esAxml(datos)) throw IllegalArgumentException("No es un XML binario de Android (AXML)")
        val bb = ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN)
        val declarado = bb.getInt(4)
        val fin = if (declarado in 8..datos.size) declarado else datos.size
        var pos = u16(bb, 2)

        var cadenas: Array<String> = emptyArray()
        var mapaRecursos = IntArray(0)
        val prefijos = HashMap<String, String>()
        val pendientes = ArrayList<Pair<String, String>>()
        val pila = ArrayList<NodoAxml>()
        var raiz: NodoAxml? = null
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var nivel = 0
        var abierto = false

        while (pos + 8 <= fin) {
            val t = u16(bb, pos)
            val hs = u16(bb, pos + 2)
            val sz = bb.getInt(pos + 4)
            if (sz < 8 || pos + sz > datos.size) break
            when (t) {
                RES_STRING_POOL -> cadenas = leerPool(bb, datos, pos)
                RES_XML_RESMAP -> {
                    val n = (sz - hs) / 4
                    mapaRecursos = IntArray(n) { bb.getInt(pos + hs + it * 4) }
                }
                RES_XML_START_NS -> {
                    val prefijo = cad(cadenas, bb.getInt(pos + 16))
                    val uri = cad(cadenas, bb.getInt(pos + 20))
                    prefijos[uri] = prefijo
                    pendientes.add(Pair(prefijo, uri))
                }
                RES_XML_END_NS -> { }
                RES_XML_START_EL -> {
                    val nombre = cad(cadenas, bb.getInt(pos + 20))
                    val attrStart = u16(bb, pos + 24)
                    val attrSize = u16(bb, pos + 26).let { if (it < 20) 20 else it }
                    val attrCount = u16(bb, pos + 28)
                    if (abierto) { sb.append(">\n"); abierto = false }
                    indentar(sb, nivel)
                    sb.append('<').append(nombre)
                    for ((p, u) in pendientes) {
                        sb.append(if (p.isEmpty()) " xmlns=\"" else " xmlns:$p=\"").append(escapar(u)).append('"')
                    }
                    pendientes.clear()
                    val mapa = LinkedHashMap<String, String>()
                    val baseAttrs = pos + 16 + attrStart
                    for (i in 0 until attrCount) {
                        val a = baseAttrs + i * attrSize
                        if (a + 20 > datos.size) break
                        val ans = bb.getInt(a)
                        val an = bb.getInt(a + 4)
                        val raw = bb.getInt(a + 8)
                        val dt = datos[a + 15].toInt() and 0xFF
                        val data = bb.getInt(a + 16)
                        var nombreAttr = cad(cadenas, an)
                        if (nombreAttr.isEmpty()) {
                            nombreAttr = if (an >= 0 && an < mapaRecursos.size) String.format("attr_0x%08x", mapaRecursos[an]) else "attr_$an"
                        }
                        val uriAttr = cad(cadenas, ans)
                        val prefijo = if (uriAttr.isNotEmpty()) prefijos[uriAttr] else null
                        val claveXml = if (!prefijo.isNullOrEmpty()) "$prefijo:$nombreAttr" else nombreAttr
                        val claveMapa = if (uriAttr == ANDROID_NS) "android:$nombreAttr" else claveXml
                        val valor = valorTexto(cadenas, raw, dt, data)
                        observador?.alAtributo(
                            uriAttr, nombreAttr,
                            if (an >= 0 && an < mapaRecursos.size) mapaRecursos[an] else 0,
                            valor, dt, data
                        )
                        sb.append(' ').append(claveXml).append("=\"").append(escapar(valor)).append('"')
                        mapa[claveMapa] = valor
                    }
                    abierto = true
                    val nodo = NodoAxml(nombre, mapa)
                    if (pila.isEmpty()) {
                        if (raiz == null) raiz = nodo
                    } else {
                        pila[pila.size - 1].hijos.add(nodo)
                    }
                    pila.add(nodo)
                    nivel++
                }
                RES_XML_END_EL -> {
                    val nombre = cad(cadenas, bb.getInt(pos + 20))
                    nivel = if (nivel > 0) nivel - 1 else 0
                    if (abierto) {
                        sb.append(" />\n")
                        abierto = false
                    } else {
                        indentar(sb, nivel)
                        sb.append("</").append(nombre).append(">\n")
                    }
                    if (pila.isNotEmpty()) pila.removeAt(pila.size - 1)
                }
                RES_XML_CDATA -> {
                    val texto = cad(cadenas, bb.getInt(pos + 16))
                    if (abierto) { sb.append(">\n"); abierto = false }
                    if (texto.isNotBlank()) {
                        indentar(sb, nivel)
                        sb.append(escapar(texto)).append('\n')
                    }
                    if (pila.isNotEmpty()) pila[pila.size - 1].texto = texto
                }
                else -> { }
            }
            pos += sz
        }
        return ResultadoAxml(raiz, sb.toString())
    }

    private fun indentar(sb: StringBuilder, n: Int) {
        for (i in 0 until n) sb.append("    ")
    }

    private fun escapar(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun leerPool(bb: ByteBuffer, datos: ByteArray, pos: Int): Array<String> {
        val hs = u16(bb, pos + 2)
        val n = bb.getInt(pos + 8)
        val flags = bb.getInt(pos + 16)
        val inicioCadenas = bb.getInt(pos + 20)
        val utf8 = (flags and 0x100) != 0
        if (n < 0 || n > 1_000_000) throw IllegalArgumentException("Tabla de cadenas corrupta")
        return Array(n) { i ->
            val off = pos + inicioCadenas + bb.getInt(pos + hs + i * 4)
            try {
                if (utf8) leerUtf8(datos, off) else leerUtf16(bb, datos, off)
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun leerUtf8(d: ByteArray, off: Int): String {
        var p = off
        var len = d[p].toInt() and 0xFF
        p++
        if ((len and 0x80) != 0) {
            p++
        }
        var blen = d[p].toInt() and 0xFF
        p++
        if ((blen and 0x80) != 0) {
            blen = ((blen and 0x7F) shl 8) or (d[p].toInt() and 0xFF)
            p++
        }
        return String(d, p, blen, Charsets.UTF_8)
    }

    private fun leerUtf16(bb: ByteBuffer, d: ByteArray, off: Int): String {
        var p = off
        var len = u16(bb, p)
        p += 2
        if ((len and 0x8000) != 0) {
            len = ((len and 0x7FFF) shl 16) or u16(bb, p)
            p += 2
        }
        return String(d, p, len * 2, Charsets.UTF_16LE)
    }

    private fun valorTexto(c: Array<String>, raw: Int, tipo: Int, data: Int): String {
        return when (tipo) {
            0x03 -> if (raw >= 0) cad(c, raw) else cad(c, data)
            0x01 -> "@" + String.format("0x%08x", data)
            0x02 -> "?" + String.format("0x%08x", data)
            0x04 -> java.lang.Float.intBitsToFloat(data).toString()
            0x05 -> complejo(data) + arrayOf("px", "dip", "sp", "pt", "in", "mm").getOrElse(data and 0xF) { "" }
            0x06 -> complejo(data) + (if ((data and 0xF) == 0) "%" else "%p")
            0x10 -> data.toString()
            0x11 -> "0x" + Integer.toHexString(data)
            0x12 -> if (data != 0) "true" else "false"
            in 0x1C..0x1F -> "#" + String.format("%08x", data)
            else -> if (raw >= 0) cad(c, raw) else "0x" + Integer.toHexString(data)
        }
    }

    private fun complejo(data: Int): String {
        val mult = floatArrayOf(1.0f / 8388608.0f, 1.0f / 32768.0f, 1.0f / 128.0f, 1.0f)
        val v = (data and 0xFFFFFF00.toInt()).toFloat() * mult[(data shr 4) and 3]
        val rounded = Math.round(v * 100f) / 100f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }
}
