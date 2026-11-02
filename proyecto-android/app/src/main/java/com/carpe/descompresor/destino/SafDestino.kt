package com.carpe.descompresor.destino

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.carpe.descompresor.util.Flujos
import com.carpe.descompresor.util.PreferenciasApp
import java.io.InputStream

class SafDestino : EstrategiaDestino {
    override val id: String = "saf"
    override val nombre: String = "Carpeta elegida (SAF)"

    private var raiz: DocumentFile? = null
    private val cacheDirs = HashMap<String, DocumentFile>()
    private var descripcionTexto: String = "Carpeta SAF"

    override fun disponible(ctx: Context): Boolean = true

    override suspend fun elegir(ctx: Context): Boolean {
        val s = PreferenciasApp(ctx).safUri()
        if (s.isEmpty()) return false
        val uri = Uri.parse(s)
        val permiso = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!permiso) return false
        val r = DocumentFile.fromTreeUri(ctx, uri) ?: return false
        if (!r.canWrite()) return false
        raiz = r
        descripcionTexto = "Carpeta SAF: " + (r.name ?: uri.toString())
        return true
    }

    override fun descripcion(): String = descripcionTexto

    private fun buscar(ruta: String): DocumentFile? {
        var cur: DocumentFile = raiz ?: return null
        for (seg in ruta.split('/')) {
            if (seg.isEmpty()) continue
            cur = cur.findFile(seg) ?: return null
        }
        return cur
    }

    private fun directorio(ruta: String): DocumentFile? {
        var cur: DocumentFile = raiz ?: return null
        if (ruta.isEmpty()) return cur
        var acumulado = ""
        for (seg in ruta.split('/')) {
            if (seg.isEmpty()) continue
            acumulado = if (acumulado.isEmpty()) seg else "$acumulado/$seg"
            val c = cacheDirs[acumulado]
            if (c != null) {
                cur = c
                continue
            }
            val existente = cur.findFile(seg)
            val sig: DocumentFile = if (existente != null && existente.isDirectory) existente
            else (cur.createDirectory(seg) ?: return null)
            cacheDirs[acumulado] = sig
            cur = sig
        }
        return cur
    }

    override suspend fun existe(ctx: Context, rutaRelativa: String): Boolean = buscar(rutaRelativa) != null

    override suspend fun escribir(ctx: Context, rutaRelativa: String, input: InputStream): Boolean {
        val nombreArchivo = rutaRelativa.substringAfterLast('/')
        val carpeta = rutaRelativa.substringBeforeLast('/', "")
        val padre = directorio(carpeta) ?: return false
        padre.findFile(nombreArchivo)?.delete()
        val doc = padre.createFile("application/octet-stream", nombreArchivo) ?: return false
        val salida = ctx.contentResolver.openOutputStream(doc.uri, "wt") ?: return false
        var escrito = 0L
        salida.use { o ->
            escrito = Flujos.copiar(input, o, PreferenciasApp(ctx).bufferBytes())
            o.flush()
        }
        val l = doc.length()
        if (l > 0L && l != escrito) return false  // SAF puede fallar en silencio en algunos proveedores
        return true
    }

    override suspend fun eliminar(ctx: Context, rutaRelativa: String): Boolean {
        val d = buscar(rutaRelativa) ?: return false
        return d.delete()
    }
}
