package com.carpe.descompresor.extraer

import android.content.Context
import com.carpe.descompresor.util.PreferenciasApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Datos mínimos para reanudar una extracción interrumpida (la app se cerró o el sistema la mató). */
class DatosPendiente(
    val tipo: String,                 // "contenedor" o "lote"
    val origen: String,               // Uri del contenedor (tipo contenedor)
    val nombre: String,
    val uris: List<String>,           // Uris de los archivos (tipo lote)
    val destinoId: String,
    val destinoDetalle: String,
    val preservar: Boolean,
    val verificarCrc: Boolean,
    val rutas: List<String>
)

object Pendiente {
    private fun archivo(ctx: Context): File = File(ctx.filesDir, "pendiente.json")

    fun existe(ctx: Context): Boolean = archivo(ctx).exists()

    fun guardar(ctx: Context, d: DatosPendiente) {
        val o = JSONObject()
        o.put("tipo", d.tipo)
        o.put("origen", d.origen)
        o.put("nombre", d.nombre)
        o.put("destinoId", d.destinoId)
        o.put("destinoDetalle", d.destinoDetalle)
        o.put("preservar", d.preservar)
        o.put("crc", d.verificarCrc)
        val u = JSONArray()
        for (x in d.uris) u.put(x)
        o.put("uris", u)
        val r = JSONArray()
        for (x in d.rutas) r.put(x)
        o.put("rutas", r)
        archivo(ctx).writeText(o.toString())
    }

    fun leer(ctx: Context): DatosPendiente? {
        val f = archivo(ctx)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val uris = ArrayList<String>()
            val ua = o.optJSONArray("uris")
            if (ua != null) for (i in 0 until ua.length()) uris.add(ua.getString(i))
            val rutas = ArrayList<String>()
            val ra = o.optJSONArray("rutas")
            if (ra != null) for (i in 0 until ra.length()) rutas.add(ra.getString(i))
            DatosPendiente(
                o.getString("tipo"), o.optString("origen", ""), o.optString("nombre", ""), uris,
                o.getString("destinoId"), o.optString("destinoDetalle", ""),
                o.optBoolean("preservar", true), o.optBoolean("crc", true), rutas
            )
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    fun borrar(ctx: Context) {
        archivo(ctx).delete()
        PreferenciasApp(ctx).poner("pendiente_en_curso", "")
    }
}
