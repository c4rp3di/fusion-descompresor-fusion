package com.carpe.descompresor.extraer

import android.content.Context
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.destino.EstrategiaDestino
import com.carpe.descompresor.destino.InternoDestino
import com.carpe.descompresor.util.ContadorInput
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream

class CancelacionExtraccion : RuntimeException("Extracción cancelada")

class Extractor(
    ctx: Context,
    private val contenedor: Contenedor,
    private val destino: EstrategiaDestino,
    private val op: OpcionesExtraccion,
    private val alProgreso: (Progreso) -> Unit
) {
    private val app: Context = ctx.applicationContext
    private val prefs = PreferenciasApp(app)
    private val lim = Seguridad.limites(prefs)

    private var hechos = 0
    private var total = 0
    private var bytesAcum = 0L
    private var bytesTotal = 0L
    private var ultimoEmit = 0L
    private var actual = ""

    private fun emitir(forzar: Boolean) {
        val ahora = System.currentTimeMillis()
        if (!forzar && ahora - ultimoEmit < 150L) return
        ultimoEmit = ahora
        alProgreso(Progreso(true, actual, hechos, total, bytesAcum, bytesTotal, null, ""))
    }

    suspend fun ejecutar(rutas: Set<String>): InformeExtraccion {
        val informe = InformeExtraccion()
        if (!destino.elegir(app)) {
            throw IllegalStateException("El destino «${destino.nombre}» no está disponible o no tiene permiso de escritura")
        }
        informe.destino = destino.descripcion()
        if (destino is InternoDestino) informe.carpetaLocal = destino.raiz

        val seleccion = contenedor.entradas.filter { !it.esDirectorio && it.ruta in rutas }
        total = seleccion.size
        bytesTotal = seleccion.sumOf { if (it.tamano > 0L) it.tamano else 0L }
        emitir(true)

        try {
            contenedor.recorrer(rutas) { e, flujo ->
                if (EstadoExtraccion.cancelar) throw CancelacionExtraccion()
                actual = e.ruta
                procesar(e, flujo, informe)
                prefs.poner("pendiente_en_curso", "")
                hechos++
                emitir(true)
            }
        } catch (c: CancelacionExtraccion) {
            informe.cancelado = true
        }
        prefs.sumar("stat_extraidas", informe.extraidos.size.toLong())
        prefs.sumar("stat_bytes", informe.bytes)
        return informe
    }

    private suspend fun procesar(e: Entrada, flujo: InputStream, informe: InformeExtraccion) {
        val limpia = Seguridad.limpiarRuta(e.ruta)
        if (limpia == null) {
            informe.omitidos.add(Pair(e.ruta, "ruta insegura (path traversal)"))
            return
        }
        if (e.cifrada) {
            informe.omitidos.add(Pair(e.ruta, "cifrada o método no soportado"))
            return
        }
        if (e.tamano > lim.maxEntradaBytes) {
            informe.omitidos.add(Pair(e.ruta, "supera el tamaño máximo por entrada"))
            return
        }
        if (Seguridad.ratioExcesivo(e.tamano, e.tamanoComprimido, lim.ratioMax)) {
            informe.omitidos.add(Pair(e.ruta, "ratio de compresión excesivo (posible zip bomb)"))
            return
        }
        if (informe.bytes + (if (e.tamano > 0L) e.tamano else 0L) > lim.maxTotalBytes) {
            informe.omitidos.add(Pair(e.ruta, "supera el tamaño total acumulado permitido"))
            return
        }

        val relativa = if (op.preservarEstructura) limpia else limpia.substringAfterLast('/')
        var destRuta = if (op.prefijo.isEmpty()) relativa else op.prefijo + "/" + relativa
        if (destino.existe(app, destRuta)) {
            if (op.reanudar) {
                if (destRuta != op.enCurso) {
                    informe.yaExistian++
                    return
                }
                // era el archivo en curso al interrumpirse: se rehace (se sobrescribe)
            } else {
                when (op.siExiste) {
                    SiExiste.OMITIR -> {
                        informe.omitidos.add(Pair(e.ruta, "ya existe"))
                        return
                    }
                    SiExiste.RENOMBRAR -> destRuta = nombreLibre(destRuta)
                    SiExiste.SOBRESCRIBIR -> {}
                }
            }
        }
        prefs.poner("pendiente_en_curso", destRuta)

        val contador = ContadorInput(flujo, lim.maxEntradaBytes) { n ->
            bytesAcum += n
            emitir(false)
        }
        val crc = CRC32()
        val verificado = CheckedInputStream(contador, crc)
        val ok: Boolean = try {
            destino.escribir(app, destRuta, verificado)
        } catch (c: CancelacionExtraccion) {
            throw c
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Exception) {
            try { destino.eliminar(app, destRuta) } catch (x: Exception) { }
            informe.fallidos.add(Pair(e.ruta, t.message ?: t.javaClass.simpleName))
            return
        }
        if (!ok) {
            try { destino.eliminar(app, destRuta) } catch (x: Exception) { }
            informe.fallidos.add(Pair(e.ruta, "no se pudo escribir en el destino"))
            return
        }
        val leidos = contador.total
        if (e.tamano >= 0L && leidos != e.tamano) {
            try { destino.eliminar(app, destRuta) } catch (x: Exception) { }
            informe.fallidos.add(Pair(e.ruta, "tamaño distinto del declarado ($leidos de ${e.tamano} bytes)"))
            return
        }
        if (op.verificarCrc && e.crc >= 0L && crc.value != e.crc) {
            try { destino.eliminar(app, destRuta) } catch (x: Exception) { }
            informe.fallidos.add(Pair(e.ruta, "CRC inválido"))
            return
        }
        informe.extraidos.add(e.ruta)
        informe.bytes += leidos
    }

    private suspend fun nombreLibre(ruta: String): String {
        val carpeta = ruta.substringBeforeLast('/', "")
        val nombre = ruta.substringAfterLast('/')
        val punto = nombre.lastIndexOf('.')
        val base = if (punto > 0) nombre.substring(0, punto) else nombre
        val ext = if (punto > 0) nombre.substring(punto) else ""
        var i = 1
        while (true) {
            val cand = "$base ($i)$ext"
            val r = if (carpeta.isEmpty()) cand else "$carpeta/$cand"
            if (i > 9999 || !destino.existe(app, r)) return r
            i++
        }
    }
}
