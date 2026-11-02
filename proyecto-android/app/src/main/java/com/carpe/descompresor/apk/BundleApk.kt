package com.carpe.descompresor.apk

import android.content.Context
import android.os.Build
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.util.Iconos
import java.util.Locale

class PlanBundle(
    val todos: List<Entrada>,
    val elegidos: List<Entrada>,
    val hayObb: Boolean,
    val universal: Boolean
)

/** Paquetes de APKs divididos (.apks de bundletool, .xapk, .apkm). */
object BundleApk {
    private val extBundle = setOf("apks", "xapk", "apkm")
    private val abis = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64")
    private val densidades = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)
    private val idioma = Regex("^[a-z]{2,3}(_[a-z0-9]{2,4})?$")

    fun apksDe(c: Contenedor): List<Entrada> =
        c.entradas.filter { !it.esDirectorio && it.nombre.endsWith(".apk", ignoreCase = true) }

    fun esBundle(c: Contenedor): Boolean {
        if (c.esApk) return false
        val a = apksDe(c)
        if (a.isEmpty()) return false
        return Iconos.extension(c.nombre) in extBundle || a.size >= 2
    }

    /** Parte que identifica el tipo de split (abi, densidad, idioma, master…); null = siempre se instala. */
    private fun token(nombre: String): String? {
        val sinExt = nombre.substringAfterLast('/').substringBeforeLast('.')
        val bajo = sinExt.lowercase()
        val t = when {
            "split_config." in bajo -> bajo.substringAfter("split_config.")
            "config." in bajo -> bajo.substringAfterLast("config.")
            '-' in bajo -> bajo.substringAfterLast('-')
            else -> return null
        }
        return t.replace('-', '_')
    }

    fun plan(ctx: Context, c: Contenedor): PlanBundle {
        var todos = apksDe(c)
        val hayObb = c.entradas.any { it.ruta.endsWith(".obb", ignoreCase = true) }
        val enSplits = todos.filter { it.ruta.replace('\\', '/').startsWith("splits/") }
        if (enSplits.isNotEmpty()) {
            todos = enSplits
        } else {
            val uni = todos.firstOrNull { it.nombre.equals("universal.apk", ignoreCase = true) }
            if (uni != null) return PlanBundle(listOf(uni), listOf(uni), hayObb, true)
            val sin = todos.filter { !it.ruta.replace('\\', '/').startsWith("standalones/") }
            if (sin.isNotEmpty()) todos = sin
        }

        val dpi = ctx.resources.displayMetrics.densityDpi
        val abisDisp = Build.SUPPORTED_ABIS.map { it.lowercase().replace('-', '_') }.toSet()
        val idiomaDisp = Locale.getDefault().language.lowercase()
        val tokens = HashMap<Entrada, String?>()
        for (e in todos) tokens[e] = token(e.nombre)
        val presentes = tokens.values.filterNotNull().filter { it in densidades }
        val mejorDpi = presentes.minByOrNull { Math.abs(densidades.getValue(it) - dpi) }

        val elegidos = todos.filter { e ->
            val t = tokens[e]
            when {
                t == null || t == "master" -> true
                t in abis -> t in abisDisp
                t in densidades -> t == mejorDpi
                idioma.matches(t) -> t.substringBefore('_') == idiomaDisp
                else -> true
            }
        }
        return PlanBundle(todos, if (elegidos.isEmpty()) todos else elegidos, hayObb, false)
    }
}
