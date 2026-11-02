package com.carpe.descompresor.motor

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream

class ContenedorRar(nombre: String, archivo: File, private val cacheTemp: File, maxEntradas: Int) :
    Contenedor(nombre, "RAR", archivo) {

    private val rar = Archive(archivo)
    private val cabeceras = HashMap<String, FileHeader>()
    override val entradas: List<Entrada>

    init {
        val lista = ArrayList<Entrada>()
        try {
            for (h in rar.fileHeaders) {
                if (lista.size >= maxEntradas) {
                    throw IllegalStateException("Demasiadas entradas (más de $maxEntradas; ajustable en Ajustes)")
                }
                val ruta = nombreDe(h).replace('\\', '/')
                lista.add(Entrada(ruta, h.isDirectory, h.fullUnpackSize, -1L, "RAR", -1L, 0L))
                cabeceras[ruta] = h
            }
        } catch (t: Throwable) {
            try { rar.close() } catch (x: Exception) { }
            throw t
        }
        entradas = lista
    }

    /** junrar cambió el nombre del getter entre versiones: se resuelve por reflexión. */
    private fun nombreDe(h: FileHeader): String {
        for (m in listOf("getFileName", "getFileNameString")) {
            try {
                val r = h.javaClass.getMethod(m).invoke(h)
                if (r is String && r.isNotEmpty()) return r
            } catch (e: Exception) {
                // probar el siguiente nombre
            }
        }
        return "desconocido"
    }

    override fun abrirEntrada(entrada: Entrada): InputStream {
        val h = cabeceras[entrada.ruta] ?: throw FileNotFoundException(entrada.ruta)
        if (!cacheTemp.exists()) cacheTemp.mkdirs()
        val tmp = File.createTempFile("rar_", ".tmp", cacheTemp)
        try {
            FileOutputStream(tmp).use { o -> rar.extractFile(h, o) }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return object : FilterInputStream(FileInputStream(tmp)) {
            override fun close() {
                try { super.close() } finally { tmp.delete() }
            }
        }
    }

    override fun close() {
        try { rar.close() } catch (x: Exception) { }
    }
}

class MotorRar : MotorContenedor {
    override val id: String = "rar"
    override fun puedeAbrir(cabecera: ByteArray, nombre: String): Boolean =
        DetectorFormato.detectar(cabecera, nombre) == TipoFormato.RAR

    override suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        return ContenedorRar(nombre, archivo, File(ctx.cacheDir, "rar_tmp"), lim.maxEntradas)
    }
}
