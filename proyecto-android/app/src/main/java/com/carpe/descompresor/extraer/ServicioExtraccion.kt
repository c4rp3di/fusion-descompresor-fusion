package com.carpe.descompresor.extraer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.carpe.descompresor.MainActivity
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.destino.CatalogoDestinos
import com.carpe.descompresor.destino.EstrategiaDestino
import com.carpe.descompresor.destino.InternoDestino
import com.carpe.descompresor.motor.CatalogoMotores
import com.carpe.descompresor.util.ArchivoInbox
import com.carpe.descompresor.util.CopiaCancelada
import com.carpe.descompresor.util.CopiaLocal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ServicioExtraccion : Service() {

    companion object {
        const val CANAL = "extraccion"
        const val ID_PROGRESO = 1001
        const val ID_FINAL = 1002
        const val ACCION_CANCELAR = "com.carpe.descompresor.CANCELAR"

        fun iniciar(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ServicioExtraccion::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trabajando = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_CANCELAR) {
            EstadoExtraccion.cancelar = true
            return START_NOT_STICKY
        }
        crearCanal()
        ServiceCompat.startForeground(
            this, ID_PROGRESO, construir("Preparando extracción…", 0, 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val sol = EstadoExtraccion.solicitud
        if (sol == null || trabajando) {
            if (!trabajando) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        trabajando = true
        EstadoExtraccion.cancelar = false
        EstadoExtraccion.estado.value = Progreso(activo = true, mensaje = "Preparando…")

        scope.launch {
            var informe: InformeExtraccion? = null
            var error: String? = null
            try {
                val destino = CatalogoDestinos.crear(sol.destinoId, sol.destinoDetalle)
                if (!destino.elegir(applicationContext)) {
                    throw IllegalStateException("El destino «${destino.nombre}» no está disponible o no tiene permiso de escritura")
                }
                val detalle = if (destino is InternoDestino) (destino.raiz?.absolutePath ?: "") else sol.destinoDetalle
                if (!sol.opciones.reanudar) guardarPendiente(sol, detalle)
                informe = if (sol.lote != null) ejecutarLote(sol, destino) else ejecutarContenedor(sol, destino)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            // Si el proceso muere antes de llegar aquí, queda el registro para reanudar.
            Pendiente.borrar(applicationContext)

            val inf = informe
            EstadoExtraccion.estado.value = if (inf != null) {
                Progreso(false, "", inf.extraidos.size, inf.extraidos.size, inf.bytes, inf.bytes, inf, "")
            } else {
                Progreso(false, "", 0, 0, 0L, 0L, null, "Error: " + (error ?: "desconocido"))
            }
            val texto = if (inf != null) inf.resumen() else "Error: " + (error ?: "desconocido")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val fin = NotificationCompat.Builder(this@ServicioExtraccion, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Extracción terminada")
                .setContentText(texto)
                .setContentIntent(abrirApp())
                .setAutoCancel(true)
                .build()
            nm.notify(ID_FINAL, fin)
            trabajando = false
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun publicar(p: Progreso) {
        EstadoExtraccion.estado.value = p
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_PROGRESO, construir("${p.hechos}/${p.total} · ${p.archivoActual}", p.total, p.hechos, p.total == 0))
    }

    private fun guardarPendiente(sol: SolicitudExtraccion, detalle: String) {
        val lote = sol.lote
        val cont = sol.contenedor
        val o = sol.opciones
        if (lote != null) {
            Pendiente.guardar(
                applicationContext,
                DatosPendiente("lote", "", "${lote.size} archivos", lote.map { it.toString() }, sol.destinoId, detalle,
                    o.preservarEstructura, o.verificarCrc, emptyList())
            )
        } else if (sol.origen != null && cont != null) {
            Pendiente.guardar(
                applicationContext,
                DatosPendiente("contenedor", sol.origen, cont.nombre, emptyList(), sol.destinoId, detalle,
                    o.preservarEstructura, o.verificarCrc, sol.rutas.toList())
            )
        }
    }

    private suspend fun ejecutarContenedor(sol: SolicitudExtraccion, destino: EstrategiaDestino): InformeExtraccion {
        val c = sol.contenedor ?: throw IllegalStateException("No hay contenedor abierto")
        val extractor = Extractor(applicationContext, c, destino, sol.opciones) { p -> publicar(p) }
        return extractor.ejecutar(sol.rutas)
    }

    private fun nombreCarpeta(nombre: String): String {
        var n = nombre.substringBeforeLast('.', nombre)
        if (n.endsWith(".tar", ignoreCase = true)) n = n.dropLast(4)
        n = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return n.ifEmpty { "archivo" }
    }

    /** Extrae varios archivos, cada uno en su propia subcarpeta, uno tras otro. */
    private suspend fun ejecutarLote(sol: SolicitudExtraccion, destino: EstrategiaDestino): InformeExtraccion {
        val app = applicationContext
        val uris = sol.lote ?: emptyList()
        val total = InformeExtraccion()
        total.destino = destino.descripcion()
        total.permiteReintento = false
        if (destino is InternoDestino) total.carpetaLocal = destino.raiz

        for ((i, uri) in uris.withIndex()) {
            if (EstadoExtraccion.cancelar) {
                total.cancelado = true
                break
            }
            val nombreArchivo = CopiaLocal.nombreDe(app, uri)
            val etiqueta = "[${i + 1}/${uris.size}] $nombreArchivo"
            publicar(Progreso(true, "$etiqueta · copiando…", i, uris.size, 0L, 0L, null, ""))
            var c: Contenedor? = null
            try {
                val res = ArchivoInbox.recibir(app, uri, null) { EstadoExtraccion.cancelar }
                val abierto: Contenedor = try {
                    CatalogoMotores.abrirArchivo(app, res.archivo, res.nombre)
                } catch (t: Throwable) {
                    CopiaLocal.borrar(res.archivo)
                    throw t
                }
                c = abierto
                val todas = abierto.entradas.filter { !it.esDirectorio }.map { it.ruta }.toSet()
                val opciones = sol.opciones.copy(prefijo = nombreCarpeta(res.nombre))
                val extractor = Extractor(app, abierto, destino, opciones) { p ->
                    publicar(p.copy(archivoActual = etiqueta + " · " + p.archivoActual))
                }
                val inf = extractor.ejecutar(todas)
                for (r in inf.extraidos) total.extraidos.add("$nombreArchivo ▸ $r")
                for ((r, m) in inf.fallidos) total.fallidos.add(Pair("$nombreArchivo ▸ $r", m))
                for ((r, m) in inf.omitidos) total.omitidos.add(Pair("$nombreArchivo ▸ $r", m))
                total.bytes += inf.bytes
                total.yaExistian += inf.yaExistian
                if (inf.cancelado) {
                    total.cancelado = true
                    break
                }
            } catch (t: CopiaCancelada) {
                total.cancelado = true
                break
            } catch (t: Throwable) {
                total.fallidos.add(Pair(nombreArchivo, t.message ?: t.javaClass.simpleName))
            } finally {
                val aCerrar = c
                if (aCerrar != null) {
                    try { aCerrar.close() } catch (x: Exception) { }
                    CopiaLocal.borrar(aCerrar.archivo)
                }
            }
        }
        return total
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(NotificationChannel(CANAL, "Extracciones", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun abrirApp(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun construir(texto: String, max: Int, actual: Int, indeterminado: Boolean): Notification {
        val cancelar = PendingIntent.getService(
            this, 1,
            Intent(this, ServicioExtraccion::class.java).setAction(ACCION_CANCELAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Extrayendo")
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, actual, indeterminado)
            .setContentIntent(abrirApp())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelar)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
