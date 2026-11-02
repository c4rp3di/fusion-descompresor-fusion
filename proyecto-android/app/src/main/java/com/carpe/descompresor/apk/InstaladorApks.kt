package com.carpe.descompresor.apk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/** Instala uno o varios APK (splits) en una sola sesión de PackageInstaller. */
object InstaladorApks {
    const val ACCION = "com.carpe.descompresor.INSTALACION"

    /** Escribe todos los archivos en la sesión y la confirma. Llamar desde un hilo de fondo. */
    fun instalar(ctx: Context, archivos: List<File>) {
        val app = ctx.applicationContext
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        val session = pi.openSession(id)
        try {
            for ((i, f) in archivos.withIndex()) {
                f.inputStream().use { entrada ->
                    session.openWrite("split_$i.apk", 0L, f.length()).use { salida ->
                        entrada.copyTo(salida)
                        session.fsync(salida)
                    }
                }
            }
            val intent = Intent(app, ReceptorInstalacion::class.java).setAction(ACCION)
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendiente = PendingIntent.getBroadcast(app, id, intent, flags)
            session.commit(pendiente.intentSender)
        } catch (t: Throwable) {
            try { session.abandon() } catch (x: Exception) { }
            throw t
        } finally {
            session.close()
        }
    }
}
