package com.carpe.descompresor.destino

import android.content.Context
import com.carpe.descompresor.util.Flujos
import com.carpe.descompresor.util.PreferenciasApp
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class InternoDestino(private val existente: String = "") : EstrategiaDestino {
    override val id: String = "interno"
    override val nombre: String = "Interno de la app (extraer y compartir)"

    var raiz: File? = null
        private set

    override fun disponible(ctx: Context): Boolean = true

    override suspend fun elegir(ctx: Context): Boolean {
        if (raiz != null) return true
        val d = if (existente.isNotEmpty()) File(existente) else File(ctx.filesDir, "extracciones/" + System.currentTimeMillis())
        d.mkdirs()
        raiz = d
        return d.isDirectory
    }

    override fun descripcion(): String = "Interno: " + (raiz?.absolutePath ?: "?")

    private fun resolver(ruta: String): File? {
        val b = raiz ?: return null
        val f = File(b, ruta)
        if (!f.canonicalPath.startsWith(b.canonicalPath + File.separator)) return null
        return f
    }

    override suspend fun existe(ctx: Context, rutaRelativa: String): Boolean = resolver(rutaRelativa)?.exists() == true

    override suspend fun escribir(ctx: Context, rutaRelativa: String, input: InputStream): Boolean {
        val f = resolver(rutaRelativa) ?: return false
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { o ->
            Flujos.copiar(input, o, PreferenciasApp(ctx).bufferBytes())
            o.flush()
        }
        return true
    }

    override suspend fun eliminar(ctx: Context, rutaRelativa: String): Boolean {
        val f = resolver(rutaRelativa) ?: return false
        return f.delete()
    }
}
