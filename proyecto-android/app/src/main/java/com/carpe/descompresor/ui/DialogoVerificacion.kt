package com.carpe.descompresor.ui

import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.carpe.descompresor.extraer.Verificador
import com.carpe.descompresor.util.Formato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

object DialogoVerificacion {
    fun mostrar(activity: AppCompatActivity, vm: ContenedorViewModel) {
        val c = vm.contenedor ?: return
        val cancelar = AtomicBoolean(false)

        val raiz = Ui.vertical(activity, 16)
        val texto = Ui.texto(activity, "Leyendo…", 13f)
        val barra = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        barra.isIndeterminate = false
        barra.max = 1000
        val detalle = Ui.texto(activity, "", 11f)
        raiz.addView(texto)
        raiz.addView(barra, Ui.anchoTotal(activity, 8))
        raiz.addView(detalle)

        val dialogo = AlertDialog.Builder(activity)
            .setTitle("Verificando integridad")
            .setView(raiz)
            .setCancelable(false)
            .setNegativeButton("Cancelar", null)
            .create()
        dialogo.show()
        dialogo.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            cancelar.set(true)
            texto.text = "Cancelando…"
        }

        val totalBytes = c.entradas.filter { !it.esDirectorio }.sumOf { if (it.tamano > 0L) it.tamano else 0L }
        var ultimoUi = 0L

        activity.lifecycleScope.launch {
            val inf = withContext(Dispatchers.IO) {
                Verificador.verificar(
                    c,
                    { hechos, total, bytes, actual ->
                        val ahora = System.currentTimeMillis()
                        if (ahora - ultimoUi > 200L) {
                            ultimoUi = ahora
                            activity.runOnUiThread {
                                if (totalBytes > 0L) barra.progress = ((bytes * 1000L) / totalBytes).toInt().coerceIn(0, 1000)
                                texto.text = "$hechos/$total archivos · ${Formato.bytes(bytes)}"
                                detalle.text = actual
                            }
                        }
                    },
                    { cancelar.get() }
                )
            }
            dialogo.dismiss()
            val tv = Ui.monoespaciado(activity, inf.detalle(), 12f)
            val p = Ui.dp(activity, 16)
            tv.setPadding(p, p, p, p)
            val sv = ScrollView(activity)
            sv.addView(tv)
            AlertDialog.Builder(activity)
                .setTitle(
                    when {
                        inf.cancelado -> "Verificación cancelada"
                        inf.fallos.isEmpty() -> "✔ Archivo íntegro"
                        else -> "⚠ Se encontraron errores"
                    }
                )
                .setView(sv)
                .setPositiveButton("Cerrar", null)
                .show()
        }
    }
}
