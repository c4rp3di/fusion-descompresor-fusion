package com.carpe.descompresor.ui

import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.carpe.descompresor.apk.GestorKeystore
import com.carpe.descompresor.apk.LectorFirma
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Flujo de importación de un keystore ya copiado a un archivo temporal: contraseñas, alias,
 * huella SHA-256 del certificado y comparación con el APK abierto, antes de guardarlo.
 */
object ImportadorKeystore {

    private fun pedirTexto(activity: AppCompatActivity, titulo: String, inicial: String, cb: (String) -> Unit, alCancelar: () -> Unit) {
        val campo = EditText(activity)
        campo.setText(inicial)
        campo.setSingleLine(true)
        campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val cont = Ui.vertical(activity, 16)
        cont.addView(campo)
        AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setView(cont)
            .setCancelable(false)
            .setNegativeButton("Cancelar") { _, _ -> alCancelar() }
            .setPositiveButton("Aceptar") { _, _ -> cb(campo.text.toString()) }
            .show()
    }

    fun iniciar(activity: AppCompatActivity, vm: ContenedorViewModel, tmp: File, nombre: String, alCompletar: () -> Unit) {
        // "android" es la contraseña que usa el workflow de Fusion; se propone pero se puede cambiar.
        pedirTexto(activity, "Contraseña del keystore", "android", { ps ->
            activity.lifecycleScope.launch {
                try {
                    val aliases = withContext(Dispatchers.IO) { GestorKeystore.abrirAlmacen(tmp, ps.toCharArray()).aliases() }
                    if (aliases.isEmpty()) throw IOException("El keystore no contiene claves privadas")
                    if (aliases.size == 1) {
                        pedirClave(activity, vm, tmp, nombre, aliases[0], ps, alCompletar)
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle("Elige el alias")
                            .setCancelable(false)
                            .setItems(aliases.toTypedArray()) { _, i -> pedirClave(activity, vm, tmp, nombre, aliases[i], ps, alCompletar) }
                            .setNegativeButton("Cancelar") { _, _ -> tmp.delete() }
                            .show()
                    }
                } catch (t: Throwable) {
                    tmp.delete()
                    Ui.toast(activity, Ui.mensajeError(t))
                }
            }
        }, { tmp.delete() })
    }

    private fun pedirClave(
        activity: AppCompatActivity, vm: ContenedorViewModel, tmp: File, nombre: String,
        alias: String, passStore: String, alCompletar: () -> Unit
    ) {
        pedirTexto(activity, "Contraseña de la clave «$alias»", passStore, { pk ->
            activity.lifecycleScope.launch {
                try {
                    val r = withContext(Dispatchers.IO) {
                        val almacen = GestorKeystore.abrirAlmacen(tmp, passStore.toCharArray())
                        val clave = almacen.clave(alias, pk.toCharArray())
                        val cert = clave.certificados.firstOrNull() ?: throw IOException("La clave no tiene certificado")
                        val huella = LectorFirma.huella(cert)
                        val c = vm.contenedor
                        val comparacion: String = if (c != null && c.esApk) {
                            val huellas = LectorFirma.leer(c.archivo).huellasSha256
                            if (huellas.any { it.equals(huella, ignoreCase = true) }) {
                                "✔ Coincide con la firma del APK abierto («${c.nombre}»): sirve para actualizarlo."
                            } else {
                                "✘ No coincide con la firma del APK abierto («${c.nombre}»)."
                            }
                        } else {
                            "No hay ningún APK abierto con el que comparar."
                        }
                        Pair(huella, comparacion)
                    }
                    val tv = Ui.monoespaciado(activity, "Alias: $alias\nSHA-256:\n${r.first}\n\n${r.second}", 12f)
                    val p = Ui.dp(activity, 16)
                    tv.setPadding(p, p, p, p)
                    AlertDialog.Builder(activity)
                        .setTitle("Confirmar keystore")
                        .setView(tv)
                        .setCancelable(false)
                        .setNegativeButton("Cancelar") { _, _ -> tmp.delete() }
                        .setPositiveButton("Importar") { _, _ ->
                            activity.lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) { GestorKeystore.guardar(activity, tmp, nombre, alias, passStore, pk) }
                                    Ui.toast(activity, "✔ Keystore importada y activada")
                                    alCompletar()
                                } catch (t: Throwable) {
                                    tmp.delete()
                                    Ui.toast(activity, "No se pudo importar: " + Ui.mensajeError(t))
                                }
                            }
                        }
                        .show()
                } catch (t: Throwable) {
                    tmp.delete()
                    Ui.toast(activity, "No se pudo leer la clave: " + Ui.mensajeError(t))
                }
            }
        }, { tmp.delete() })
    }
}
