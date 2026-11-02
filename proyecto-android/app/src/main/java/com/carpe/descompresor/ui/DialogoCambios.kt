package com.carpe.descompresor.ui

import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.carpe.descompresor.apk.Reempaquetador
import com.carpe.descompresor.motor.EdicionZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Lista los cambios pendientes sobre el ZIP/APK abierto y permite guardarlos o descartarlos. */
object DialogoCambios {

    private fun texto(vm: ContenedorViewModel): String {
        val c = vm.contenedor
        val cambios = vm.cambiosApk
        val originales = c?.entradas?.map { it.ruta }?.toSet() ?: emptySet()
        val sb = StringBuilder()
        if (cambios.reemplazos.isNotEmpty()) {
            sb.append("Añadidos o reemplazados (${cambios.reemplazos.size})\n")
            for (k in cambios.reemplazos.keys.take(40)) {
                sb.append(if (k in originales) "  ✎ " else "  ＋ ").append(k).append('\n')
            }
            if (cambios.reemplazos.size > 40) sb.append("  … y ${cambios.reemplazos.size - 40} más\n")
            sb.append('\n')
        }
        if (cambios.renombres.isNotEmpty()) {
            sb.append("Renombrados o movidos (${cambios.renombres.size})\n")
            for ((o, d) in cambios.renombres.entries.take(40)) sb.append("  ").append(o).append(" → ").append(d).append('\n')
            if (cambios.renombres.size > 40) sb.append("  … y ${cambios.renombres.size - 40} más\n")
            sb.append('\n')
        }
        if (cambios.eliminados.isNotEmpty()) {
            sb.append("Eliminados (${cambios.eliminados.size})\n")
            for (k in cambios.eliminados.take(40)) sb.append("  ✘ ").append(k).append('\n')
            if (cambios.eliminados.size > 40) sb.append("  … y ${cambios.eliminados.size - 40} más\n")
        }
        if (sb.isEmpty()) sb.append("No hay cambios pendientes.")
        return sb.toString()
    }

    fun mostrar(activity: AppCompatActivity, vm: ContenedorViewModel, lanz: Lanzadores) {
        val c = vm.contenedor ?: return
        val tv = Ui.monoespaciado(activity, texto(vm), 12f)
        val pad = Ui.dp(activity, 16)
        tv.setPadding(pad, pad, pad, pad)
        val sv = ScrollView(activity)
        sv.addView(tv)

        val b = AlertDialog.Builder(activity)
            .setTitle("Cambios pendientes (${vm.cambiosApk.total()})")
            .setView(sv)
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Descartar todo") { _, _ ->
                vm.cambiosApk.limpiar()
                vm.notificar()
            }
        if (c.esApk) {
            b.setMessage("Para aplicar estos cambios ve a la pestaña APK → «Reempaquetar → alinear → firmar».")
        } else if (EdicionZip.editable(c)) {
            b.setPositiveButton("Guardar como…") { _, _ -> guardar(activity, vm, lanz) }
        }
        b.show()
    }

    private fun nombreSugerido(nombre: String): String {
        val base = nombre.substringBeforeLast('.', nombre)
        val ext = nombre.substringAfterLast('.', "zip")
        return "$base-editado.$ext"
    }

    private fun guardar(activity: AppCompatActivity, vm: ContenedorViewModel, lanz: Lanzadores) {
        val c = vm.contenedor ?: return
        lanz.crearDocumento(nombreSugerido(c.nombre)) { uri ->
            if (uri == null) return@crearDocumento
            val espera = AlertDialog.Builder(activity)
                .setTitle("Guardando")
                .setMessage("Aplicando cambios…")
                .setView(ProgressBar(activity))
                .setCancelable(false)
                .create()
            espera.show()
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val dir = File(activity.cacheDir, "salida_apk")
                        dir.mkdirs()
                        val tmp = File(dir, "edit_" + System.currentTimeMillis() + ".zip")
                        try {
                            Reempaquetador.reconstruir(c.archivo, vm.cambiosApk, tmp, false)
                            val salida = activity.contentResolver.openOutputStream(uri, "wt")
                                ?: throw IOException("No se pudo escribir en el destino")
                            salida.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                        } finally {
                            tmp.delete()
                        }
                    }
                    espera.dismiss()
                    Ui.toast(activity, "✔ Guardado. El archivo abierto sigue siendo el original; ábrelo de nuevo para ver el resultado.")
                } catch (t: Throwable) {
                    espera.dismiss()
                    Ui.toast(activity, "No se pudo guardar: " + Ui.mensajeError(t))
                }
            }
        }
    }
}
