package com.carpe.descompresor.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object Codificacion {
    /** Devuelve (texto, nombre del charset usado). */
    fun decodificar(bytes: ByteArray, defecto: String): Pair<String, String> {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Pair(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8 (BOM)")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Pair(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Pair(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE")
        }
        // UTF-8 estricto; se toleran hasta 3 bytes cortados al final (límite de lectura).
        for (recorte in 0..3) {
            if (bytes.size - recorte < 0) break
            val t = intentarUtf8(bytes, bytes.size - recorte)
            if (t != null) return Pair(t, "UTF-8")
        }
        val cs: Charset = if (defecto.equals("Latin-1", ignoreCase = true) || defecto.equals("preguntar", ignoreCase = true))
            Charsets.ISO_8859_1 else Charsets.UTF_8
        return Pair(String(bytes, cs), cs.name() + " (por defecto)")
    }

    private fun intentarUtf8(b: ByteArray, largo: Int): String? {
        return try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            dec.decode(ByteBuffer.wrap(b, 0, largo)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    fun pareceBinario(muestra: ByteArray): Boolean {
        val n = minOf(muestra.size, 8192)
        var raros = 0
        for (i in 0 until n) {
            val v = muestra[i].toInt() and 0xFF
            if (v == 0) return true
            if (v < 9 || (v in 14..31)) raros++
        }
        return n > 0 && raros * 100 / n > 10
    }
}
