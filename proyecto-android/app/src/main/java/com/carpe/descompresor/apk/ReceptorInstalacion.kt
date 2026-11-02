package com.carpe.descompresor.apk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Recibe el resultado de la instalación (confirmación del usuario, éxito o fallo). */
class ReceptorInstalacion : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val estado = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val mensaje = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (estado) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmar: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmar != null) {
                    confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmar)
                    } catch (e: Exception) {
                        Toast.makeText(context, "No se pudo abrir la confirmación de instalación", Toast.LENGTH_LONG).show()
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "✔ Instalación completada", Toast.LENGTH_LONG).show()
            else ->
                Toast.makeText(context, "✘ La instalación falló: $mensaje", Toast.LENGTH_LONG).show()
        }
    }
}
