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
    /** Credenciales que genera por defecto el workflow de Fusion. */
    const val PASS_DEFECTO = "android"
    const val ALIAS_DEFECTO = "androiddebugkey"

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

    /** Flujo manual: pregunta contraseña del keystore, alias y contraseña de la clave (propone las de Fusion). */
    fun iniciar(activity: AppCompatActivity, vm: ContenedorViewModel, tmp: File, nombre: String, alCompletar: () -> Unit) {
        pedirTexto(activity, "Contraseña del keystore", PASS_DEFECTO, { ps ->
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

    /**
     * Prueba primero las credenciales por defecto de Fusion (android / androiddebugkey / android).
     * Si sirven, va directo a la confirmación; si no, pasa al flujo manual.
     */
    fun automatico(activity: AppCompatActivity, vm: ContenedorViewModel, tmp: File, nombre: String, alCompletar: () -> Unit) {
        activity.lifecycleScope.launch {
            val alias: String? = withContext(Dispatchers.IO) {
                try {
                    val almacen = GestorKeystore.abrirAlmacen(tmp, PASS_DEFECTO.toCharArray())
                    val aliases = almacen.aliases()
                    val a = if (ALIAS_DEFECTO in aliases) ALIAS_DEFECTO else (aliases.firstOrNull() ?: return@withContext null)
                    almacen.clave(a, PASS_DEFECTO.toCharArray())
                    a
                } catch (e: Exception) {
                    null
                }
            }
            if (alias == null) {
                Ui.toast(activity, "Las credenciales por defecto de Fusion no sirven para este keystore. Introduce las correctas.")
                iniciar(activity, vm, tmp, nombre, alCompletar)
            } else {
                confirmar(activity, vm, tmp, nombre, alias, PASS_DEFECTO, PASS_DEFECTO, alCompletar, true)
            }
        }
    }

    private fun pedirClave(
        activity: AppCompatActivity, vm: ContenedorViewModel, tmp: File, nombre: String,
        alias: String, passStore: String, alCompletar: () -> Unit
    ) {
        pedirTexto(activity, "Contraseña de la clave «$alias»", passStore, { pk ->
            confirmar(activity, vm, tmp, nombre, alias, passStore, pk, alCompletar, false)
        }, { tmp.delete() })
    }

    private fun confirmar(
        activity: AppCompatActivity, vm: ContenedorViewModel, tmp: File, nombre: String,
        alias: String, passStore: String, pk: String, alCompletar: () -> Unit, permitirCambiar: Boolean
    ) {
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
                val tv = Ui.monoespaciado(activity, "Keystore: $nombre\nAlias: $alias\nSHA-256:\n${r.first}\n\n${r.second}", 12f)
                val p = Ui.dp(activity, 16)
                tv.setPadding(p, p, p, p)
                val b = AlertDialog.Builder(activity)
                    .setTitle("Confirmar keystore")
                    .setView(tv)
                    .setCancelable(false)
                    .setNegativeButton("Cancelar") { _, _ -> tmp.delete() }
                    .setPositiveButton("Importar") { _, _ ->
                        activity.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { GestorKeystore.guardar(activity, tmp, nombre, alias, passStore, pk) }
                                alCompletar()
                                val ok = Ui.monoespaciado(
                                    activity,
                                    "«$nombre» (alias $alias) ya está importada y seleccionada como keystore activa.\n\n" +
                                        "Ya puedes pulsar «Reempaquetar → alinear → firmar».\n\nSHA-256:\n${r.first}",
                                    12f
                                )
                                ok.setPadding(p, p, p, p)
                                AlertDialog.Builder(activity)
                                    .setTitle("✔ Keystore importada correctamente")
                                    .setView(ok)
                                    .setPositiveButton("Cerrar", null)
                                    .show()
                            } catch (t: Throwable) {
                                tmp.delete()
                                Ui.toast(activity, "No se pudo importar: " + Ui.mensajeError(t))
                            }
                        }
                    }
                if (permitirCambiar) {
                    b.setNeutralButton("Cambiar credenciales…") { _, _ -> iniciar(activity, vm, tmp, nombre, alCompletar) }
                }
                b.show()
            } catch (t: Throwable) {
                tmp.delete()
                Ui.toast(activity, "No se pudo leer la clave: " + Ui.mensajeError(t))
            }
        }
    }
}
