package com.carpe.descompresor.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formato {
    fun bytes(n: Long): String {
        if (n < 0) return "?"
        if (n < 1024) return "$n B"
        val unidades = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < unidades.size - 1) {
            v /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, unidades[i])
    }

    fun fecha(ms: Long): String {
        if (ms <= 0L) return "—"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }

    fun ratio(comprimido: Long, original: Long): String {
        if (comprimido <= 0L || original < 0L) return "—"
        return String.format(Locale.US, "%.1f:1", original.toDouble() / comprimido.toDouble())
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02X", x.toInt() and 0xFF))
        return sb.toString()
    }

    fun hexConDosPuntos(b: ByteArray): String {
        val sb = StringBuilder()
        for (i in b.indices) {
            if (i > 0) sb.append(':')
            sb.append(String.format("%02X", b[i].toInt() and 0xFF))
        }
        return sb.toString()
    }
}
