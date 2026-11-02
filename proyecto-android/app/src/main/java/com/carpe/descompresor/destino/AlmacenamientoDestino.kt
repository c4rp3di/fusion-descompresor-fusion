package com.carpe.descompresor.destino

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.carpe.descompresor.util.Flujos
import com.carpe.descompresor.util.PreferenciasApp
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class AlmacenamientoDestino : EstrategiaDestino {
    override val id: String = "almacenamiento"
    override val nombre: String = "Almacenamiento (acceso total)"

    private var base: File? = null

    companion object {
        fun carpetaPorDefecto(): File = File(Environment.getExternalStorageDirectory(), "Descompresor")

        fun permisoConcedido(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= 30) {
                Environment.isExternalStorageManager()
            } else if (Build.VERSION.SDK_INT <= 28) {
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        }
    }

    override fun disponible(ctx: Context): Boolean = permisoConcedido(ctx)

    override suspend fun elegir(ctx: Context): Boolean {
        if (!disponible(ctx)) return false
        val r = PreferenciasApp(ctx).cadena("ruta_almacenamiento")
        val f = if (r.isBlank()) carpetaPorDefecto() else File(r)
        f.mkdirs()
        if (!f.isDirectory || !f.canWrite()) return false
        base = f
        return true
    }

    override fun descripcion(): String = "Carpeta: " + (base?.absolutePath ?: "?")

    private fun resolver(ruta: String): File? {
        val b = base ?: return null
        val f = File(b, ruta)
        val raizCanon = b.canonicalPath
        if (!f.canonicalPath.startsWith(raizCanon + File.separator)) return null  // path traversal
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
