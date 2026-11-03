package com.carpe.descompresor.apk

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Añade a resources.arsc un recurso de archivo nuevo (un icono) o crea un resources.arsc mínimo.
 * EXPERIMENTAL: reconstruye las tablas de cadenas y añade un tipo nuevo al final del paquete.
 */
object ArscEscritor {
    class Resultado(val arsc: ByteArray, val idRecurso: Int)

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    private fun w16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
    }
    private fun w32(b: ByteArray, o: Int, v: Int) {
        w16(b, o, v and 0xFFFF)
        w16(b, o + 2, (v ushr 16) and 0xFFFF)
    }

    private class B {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) { o.write(v and 0xFF) }
        fun u16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }
        fun bytes(b: ByteArray) { o.write(b) }
        fun tam(): Int = o.size()
        fun arr(): ByteArray = o.toByteArray()
    }

    // ---------- cadenas ----------
    private fun varint(b: B, n: Int) {
        if (n > 127) {
            b.u8(((n shr 8) and 0x7F) or 0x80)
            b.u8(n and 0xFF)
        } else {
            b.u8(n)
        }
    }

    private fun codificar(s: String, utf8: Boolean): ByteArray {
        val b = B()
        if (utf8) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            if (s.length > 0x7FFF || bytes.size > 0x7FFF) throw IOException("Cadena demasiado larga")
            varint(b, s.length)
            varint(b, bytes.size)
            b.bytes(bytes)
            b.u8(0)
        } else {
            if (s.length > 0x7FFF) {
                b.u16(((s.length shr 16) and 0x7FFF) or 0x8000)
                b.u16(s.length and 0xFFFF)
            } else {
                b.u16(s.length)
            }
            for (c in s) b.u16(c.code)
            b.u16(0)
        }
        return b.arr()
    }

    private fun crearPool(cadenas: List<String>, utf8: Boolean): ByteArray {
        val blob = B()
        val offs = IntArray(cadenas.size)
        for ((i, s) in cadenas.withIndex()) {
            offs[i] = blob.tam()
            blob.bytes(codificar(s, utf8))
        }
        while (blob.tam() % 4 != 0) blob.u8(0)
        val ss = 28 + 4 * cadenas.size
        val b = B()
        b.u16(0x0001)
        b.u16(28)
        b.u32(ss + blob.tam())
        b.u32(cadenas.size)
        b.u32(0)
        b.u32(if (utf8) 0x100 else 0)
        b.u32(ss)
        b.u32(0)
        for (o in offs) b.u32(o)
        b.bytes(blob.arr())
        return b.arr()
    }

    /** Devuelve una copia del chunk de cadenas con [s] añadida al final (índice = cuenta anterior). */
    private fun agregarCadena(chunk: ByteArray, s: String): ByteArray {
        val hs = u16(chunk, 2)
        val n = u32(chunk, 8)
        val m = u32(chunk, 12)
        val flags = u32(chunk, 16)
        val ss = u32(chunk, 20)
        val st = u32(chunk, 24)
        val utf8 = (flags and 0x100) != 0
        val hayEstilos = m > 0 && st > 0
        val finCadenas = if (hayEstilos) st else chunk.size
        if (n < 0 || ss < hs || finCadenas > chunk.size || finCadenas < ss) throw IOException("Tabla de cadenas inesperada")

        val offsetsViejos = chunk.copyOfRange(hs, hs + 4 * n)
        val estiloOffsets = chunk.copyOfRange(hs + 4 * n, hs + 4 * n + 4 * m)
        val estilos = if (hayEstilos) chunk.copyOfRange(st, chunk.size) else ByteArray(0)

        val blob = B()
        blob.bytes(chunk.copyOfRange(ss, finCadenas))
        while (blob.tam() % 4 != 0) blob.u8(0)
        val offNuevo = blob.tam()
        blob.bytes(codificar(s, utf8))
        while (blob.tam() % 4 != 0) blob.u8(0)

        val nuevoSs = hs + 4 * (n + 1) + 4 * m
        val total = nuevoSs + blob.tam() + estilos.size
        val cab = chunk.copyOfRange(0, hs)
        w32(cab, 4, total)
        w32(cab, 8, n + 1)
        w32(cab, 16, flags and 1.inv())              // se quita la marca "ordenada"
        w32(cab, 20, nuevoSs)
        w32(cab, 24, if (hayEstilos) nuevoSs + blob.tam() else 0)

        val out = B()
        out.bytes(cab)
        out.bytes(offsetsViejos)
        out.u32(offNuevo)
        out.bytes(estiloOffsets)
        out.bytes(blob.arr())
        out.bytes(estilos)
        return out.arr()
    }

    private fun leerNombres(chunk: ByteArray): List<String> {
        val hs = u16(chunk, 2)
        val n = u32(chunk, 8)
        val flags = u32(chunk, 16)
        val ss = u32(chunk, 20)
        val utf8 = (flags and 0x100) != 0
        val l = ArrayList<String>()
        for (i in 0 until n) {
            try {
                var p = ss + u32(chunk, hs + 4 * i)
                if (utf8) {
                    val l1 = chunk[p].toInt() and 0xFF
                    p += if ((l1 and 0x80) != 0) 2 else 1
                    var bl = chunk[p].toInt() and 0xFF
                    p++
                    if ((bl and 0x80) != 0) {
                        bl = ((bl and 0x7F) shl 8) or (chunk[p].toInt() and 0xFF)
                        p++
                    }
                    l.add(String(chunk, p, bl, Charsets.UTF_8))
                } else {
                    var len = u16(chunk, p)
                    p += 2
                    if ((len and 0x8000) != 0) {
                        len = ((len and 0x7FFF) shl 16) or u16(chunk, p)
                        p += 2
                    }
                    l.add(String(chunk, p, len * 2, Charsets.UTF_16LE))
                }
            } catch (e: Exception) {
                l.add("")
            }
        }
        return l
    }

    // ---------- chunks de tipo ----------
    private fun chunkSpec(tipoId: Int): ByteArray {
        val b = ByteArray(20)
        w16(b, 0, 0x0202)
        w16(b, 2, 16)
        w32(b, 4, 20)
        b[8] = tipoId.toByte()
        w32(b, 12, 1)          // una entrada
        w32(b, 16, 0x0100)     // varía la densidad
        return b
    }

    /** Un solo valor (archivo) con configuración "nodpi" para que el PNG no se reescale. */
    private fun chunkTipo(tipoId: Int, hs: Int, cfg: Int, claveIdx: Int, idxCadena: Int): ByteArray {
        val tam = hs + 4 + 16
        val b = ByteArray(tam)
        w16(b, 0, 0x0201)
        w16(b, 2, hs)
        w32(b, 4, tam)
        b[8] = tipoId.toByte()
        w32(b, 12, 1)
        w32(b, 16, hs + 4)
        w32(b, 20, cfg)
        w16(b, 20 + 14, 0xFFFF)   // densidad = nodpi
        w32(b, hs, 0)             // offset de la entrada 0
        val en = hs + 4
        w16(b, en, 8)
        w16(b, en + 2, 0)
        w32(b, en + 4, claveIdx)
        w16(b, en + 8, 8)
        b[en + 10] = 0
        b[en + 11] = 0x03         // TYPE_STRING: la cadena es la ruta del archivo
        w32(b, en + 12, idxCadena)
        return b
    }

    // ---------- API ----------
    /** [arsc] = resources.arsc actual o null si el APK no tiene. Devuelve la tabla nueva y el ID del recurso. */
    fun agregarIcono(arsc: ByteArray?, ruta: String, paquete: String): Resultado {
        if (arsc == null) return desdeCero(ruta, paquete)
        if (arsc.size < 12 || u16(arsc, 0) != 0x0002) throw IOException("resources.arsc no válido")
        val declarado = u32(arsc, 4)
        val total = if (declarado in 12..arsc.size) declarado else arsc.size
        val hsTabla = u16(arsc, 2)

        val inicios = ArrayList<Int>()
        val tamanos = ArrayList<Int>()
        val tipos = ArrayList<Int>()
        var pos = hsTabla
        while (pos + 8 <= total) {
            val t = u16(arsc, pos)
            val sz = u32(arsc, pos + 4)
            if (sz < 8 || pos + sz > arsc.size) break
            inicios.add(pos)
            tamanos.add(sz)
            tipos.add(t)
            pos += sz
        }
        val iGlobal = tipos.indexOf(0x0001)
        if (iGlobal < 0) throw IOException("resources.arsc sin tabla de cadenas")
        var iPkg = -1
        for (i in tipos.indices) {
            if (tipos[i] == 0x0200 && u32(arsc, inicios[i] + 8) == 0x7f) {
                iPkg = i
                break
            }
        }
        if (iPkg < 0) iPkg = tipos.indexOf(0x0200)
        if (iPkg < 0) throw IOException("resources.arsc sin paquete de recursos")

        val globalViejo = arsc.copyOfRange(inicios[iGlobal], inicios[iGlobal] + tamanos[iGlobal])
        val idxCadena = u32(globalViejo, 8)
        val globalNuevo = agregarCadena(globalViejo, ruta)
        val pkgViejo = arsc.copyOfRange(inicios[iPkg], inicios[iPkg] + tamanos[iPkg])
        val idPaquete = u32(pkgViejo, 8)
        val (pkgNuevo, tipoId) = reconstruirPaquete(pkgViejo, idxCadena)

        val out = B()
        out.bytes(arsc.copyOfRange(0, hsTabla))
        for (i in tipos.indices) {
            when (i) {
                iGlobal -> out.bytes(globalNuevo)
                iPkg -> out.bytes(pkgNuevo)
                else -> out.bytes(arsc.copyOfRange(inicios[i], inicios[i] + tamanos[i]))
            }
        }
        val r = out.arr()
        w32(r, 4, r.size)
        return Resultado(r, ((idPaquete and 0xFF) shl 24) or (tipoId shl 16))
    }

    private fun reconstruirPaquete(pkg: ByteArray, idxCadena: Int): Pair<ByteArray, Int> {
        if (pkg.size < 288) throw IOException("Paquete de recursos con cabecera inesperada")
        val offTipos = u32(pkg, 268)
        val offClaves = u32(pkg, 276)
        if (offTipos < 288 || offTipos + 8 > pkg.size || offClaves < offTipos) throw IOException("Paquete de recursos inesperado")
        val tamTipos = u32(pkg, offTipos + 4)
        if (offTipos + tamTipos != offClaves) throw IOException("Orden de las tablas de nombres no soportado")
        val tamClaves = u32(pkg, offClaves + 4)
        val finClaves = offClaves + tamClaves
        if (tamClaves < 28 || finClaves > pkg.size) throw IOException("Tabla de claves inesperada")

        val cabecera = pkg.copyOfRange(0, offTipos)
        val tipoPool = pkg.copyOfRange(offTipos, offClaves)
        val clavePool = pkg.copyOfRange(offClaves, finClaves)
        val resto = pkg.copyOfRange(finClaves, pkg.size)

        var maxId = 0
        var plantillaHs = 0
        var plantillaCfg = 0
        var p = 0
        while (p + 8 <= resto.size) {
            val t = u16(resto, p)
            val sz = u32(resto, p + 4)
            if (sz < 8 || p + sz > resto.size) break
            if (t == 0x0201 || t == 0x0202) {
                val id = resto[p + 8].toInt() and 0xFF
                if (id > maxId) maxId = id
            }
            if (t == 0x0201 && plantillaHs == 0) {
                plantillaHs = u16(resto, p + 2)
                plantillaCfg = u32(resto, p + 20)
            }
            p += sz
        }
        val nTipos = u32(tipoPool, 8)
        if (maxId > nTipos) throw IOException("Tabla de recursos inesperada (tipos no contiguos)")
        val tipoId = nTipos + 1
        if (tipoId > 255) throw IOException("No caben más tipos de recursos")

        val nombreTipo = if ("mipmap" in leerNombres(tipoPool)) "icono_extra" else "mipmap"
        val claveIdx = u32(clavePool, 8)
        val tipoPoolNuevo = agregarCadena(tipoPool, nombreTipo)
        val clavePoolNuevo = agregarCadena(clavePool, "ic_launcher_extra")

        val plantillaValida = plantillaHs >= 48 && plantillaCfg >= 28 && 20 + plantillaCfg <= plantillaHs && plantillaHs % 4 == 0
        val hsT = if (plantillaValida) plantillaHs else 48
        val cfgT = if (plantillaValida) plantillaCfg else 28

        val out = B()
        out.bytes(cabecera)
        out.bytes(tipoPoolNuevo)
        out.bytes(clavePoolNuevo)
        out.bytes(resto)
        out.bytes(chunkSpec(tipoId))
        out.bytes(chunkTipo(tipoId, hsT, cfgT, claveIdx, idxCadena))
        val r = out.arr()
        w32(r, 4, r.size)
        w32(r, 276, offTipos + tipoPoolNuevo.size)
        return Pair(r, tipoId)
    }

    private fun desdeCero(ruta: String, paquete: String): Resultado {
        val global = crearPool(listOf(ruta), true)
        val tipoPool = crearPool(listOf("mipmap"), true)
        val clavePool = crearPool(listOf("ic_launcher_extra"), true)
        val hs = 288
        val cab = ByteArray(hs)
        w16(cab, 0, 0x0200)
        w16(cab, 2, hs)
        w32(cab, 8, 0x7f)
        for ((i, c) in paquete.take(127).withIndex()) w16(cab, 12 + 2 * i, c.code)
        w32(cab, 268, hs)
        w32(cab, 272, 1)
        w32(cab, 276, hs + tipoPool.size)
        w32(cab, 280, 1)
        w32(cab, 284, 0)

        val pkg = B()
        pkg.bytes(cab)
        pkg.bytes(tipoPool)
        pkg.bytes(clavePool)
        pkg.bytes(chunkSpec(1))
        pkg.bytes(chunkTipo(1, 48, 28, 0, 0))
        val pk = pkg.arr()
        w32(pk, 4, pk.size)

        val tabla = B()
        tabla.u16(0x0002)
        tabla.u16(12)
        tabla.u32(0)
        tabla.u32(1)
        tabla.bytes(global)
        tabla.bytes(pk)
        val r = tabla.arr()
        w32(r, 4, r.size)
        return Resultado(r, (0x7f shl 24) or (1 shl 16))
    }
}
