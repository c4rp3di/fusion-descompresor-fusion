package com.carpe.descompresor.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class PreferenciasApp(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("ajustes_descompresor", Context.MODE_PRIVATE)

    companion object {
        val BASE: Map<String, Any> = linkedMapOf(
            "tema" to "sistema",
            "escala_fuente" to 100,
            "destino_defecto" to "saf",
            "preservar" to true,
            "si_existe" to "renombrar",
            "verificar_crc" to true,
            "buffer_kb" to 64,
            "max_contenedor_mb" to 500,
            "max_recibido_mb" to 2048,
            "max_entrada_mb" to 200,
            "max_total_mb" to 2048,
            "ratio_max" to 100,
            "max_entradas" to 10000,
            "prev_habilitada" to true,
            "prev_texto_kb" to 500,
            "prev_imagen_mp" to 8,
            "prev_html_scripts" to false,
            "codificacion_defecto" to "UTF-8",
            "firma_v1" to false,
            "firma_v2" to true,
            "firma_v3" to true,
            "verificar_tras_firmar" to true,
            "verificar_alineacion" to true,
            "modo_dev" to false,
            "saf_uri" to "",
            "ruta_almacenamiento" to "",
            "recientes" to "",
            "pendiente_en_curso" to "",
            "stat_abiertos" to 0L,
            "stat_extraidas" to 0L,
            "stat_bytes" to 0L
        )

        private val NO_EXPORTAR = setOf("recientes", "pendiente_en_curso", "saf_uri", "stat_abiertos", "stat_extraidas", "stat_bytes")
    }

    fun cadena(k: String): String = sp.getString(k, null) ?: (BASE[k] as String)
    fun entero(k: String): Int = sp.getInt(k, (BASE[k] as Number).toInt())
    fun bool(k: String): Boolean = sp.getBoolean(k, BASE[k] as Boolean)
    fun largo(k: String): Long = sp.getLong(k, (BASE[k] as Number).toLong())

    fun poner(k: String, v: Any) {
        val e = sp.edit()
        when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is String -> e.putString(k, v)
            else -> {}
        }
        e.apply()
    }

    fun sumar(k: String, n: Long) {
        poner(k, largo(k) + n)
    }

    fun inicializarSiHaceFalta(ctx: Context) {
        if (sp.getBoolean("inicializado", false)) return
        aplicarRecomendados(ctx)
        sp.edit().putBoolean("inicializado", true).apply()
    }

    fun aplicarRecomendados(ctx: Context) {
        val rec = DetectorDispositivo.recomendados(ctx)
        for ((k, v) in rec) poner(k, v)
    }

    fun restablecerTodo(ctx: Context) {
        val e = sp.edit()
        for (k in BASE.keys) if (k !in NO_EXPORTAR) e.remove(k)
        e.apply()
        aplicarRecomendados(ctx)
    }

    fun exportarJson(): String {
        val o = JSONObject()
        for ((k, base) in BASE) {
            if (k in NO_EXPORTAR) continue
            when (base) {
                is Boolean -> o.put(k, bool(k))
                is Int -> o.put(k, entero(k))
                is Long -> o.put(k, largo(k))
                is String -> o.put(k, cadena(k))
                else -> {}
            }
        }
        return o.toString(2)
    }

    fun importarJson(texto: String): Int {
        val o = JSONObject(texto)
        var n = 0
        for ((k, base) in BASE) {
            if (k in NO_EXPORTAR || !o.has(k)) continue
            when (base) {
                is Boolean -> poner(k, o.getBoolean(k))
                is Int -> poner(k, o.getInt(k))
                is Long -> poner(k, o.getLong(k))
                is String -> poner(k, o.getString(k))
                else -> continue
            }
            n++
        }
        return n
    }

    // SAF
    fun safUri(): String = cadena("saf_uri")
    fun ponerSafUri(u: String) = poner("saf_uri", u)

    fun bufferBytes(): Int = entero("buffer_kb").coerceIn(16, 1024) * 1024
}
