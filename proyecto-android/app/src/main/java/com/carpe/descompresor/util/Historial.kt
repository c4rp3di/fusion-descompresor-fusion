package com.carpe.descompresor.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Reciente(val uri: String, val nombre: String, val tamano: Long, val fecha: Long)

/** Últimos archivos abiertos (se guardan en las preferencias de la app). */
object Historial {
    private const val MAXIMO = 15

    fun lista(ctx: Context): List<Reciente> {
        val s = PreferenciasApp(ctx).cadena("recientes")
        if (s.isEmpty()) return emptyList()
        return try {
            val a = JSONArray(s)
            val l = ArrayList<Reciente>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                l.add(Reciente(o.getString("u"), o.getString("n"), o.optLong("t", -1L), o.optLong("f", 0L)))
            }
            l
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun guardar(ctx: Context, l: List<Reciente>) {
        val a = JSONArray()
        for (r in l) {
            val o = JSONObject()
            o.put("u", r.uri)
            o.put("n", r.nombre)
            o.put("t", r.tamano)
            o.put("f", r.fecha)
            a.put(o)
        }
        PreferenciasApp(ctx).poner("recientes", a.toString())
    }

    fun agregar(ctx: Context, uri: String, nombre: String, tamano: Long) {
        val l = lista(ctx).filter { it.uri != uri }.toMutableList()
        l.add(0, Reciente(uri, nombre, tamano, System.currentTimeMillis()))
        guardar(ctx, l.take(MAXIMO))
    }

    fun borrar(ctx: Context) {
        PreferenciasApp(ctx).poner("recientes", "")
    }
}
