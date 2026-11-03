package com.carpe.descompresor.ui

import android.widget.EditText
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.carpe.descompresor.apk.EscritorAxml
import com.carpe.descompresor.apk.ParserAxml
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.extraer.OpcionesExtraccion
import com.carpe.descompresor.motor.EdicionZip
import com.carpe.descompresor.util.Seguridad
import com.carpe.descompresor.extraer.SiExiste
import com.carpe.descompresor.extraer.SolicitudExtraccion
import com.carpe.descompresor.previsualizacion.Temporales
import com.carpe.descompresor.util.Formato
import com.carpe.descompresor.apk.GestorKeystore
import com.carpe.descompresor.util.Iconos
import com.carpe.descompresor.util.PreferenciasApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PantallaDetalles {

    fun textoDetalles(e: Entrada): String {
        val sb = StringBuilder()
        sb.append("Ruta: ").append(e.ruta).append('\n')
        sb.append("Tamaño: ").append(Formato.bytes(e.tamano)).append('\n')
        sb.append("Comprimido: ").append(Formato.bytes(e.tamanoComprimido)).append('\n')
        sb.append("Ratio: ").append(Formato.ratio(e.tamanoComprimido, e.tamano)).append('\n')
        sb.append("Método: ").append(e.metodo).append('\n')
        sb.append("CRC: ").append(if (e.crc >= 0L) String.format("%08X", e.crc) else "—").append('\n')
        sb.append("Fecha: ").append(Formato.fecha(e.fecha)).append('\n')
        if (e.atributos.isNotEmpty()) sb.append("Atributos: ").append(e.atributos).append('\n')
        if (e.cifrada) sb.append("⚠ Cifrada o con método no soportado\n")
        sb.append("Tipo: ").append(e.categoria.etiqueta)
        return sb.toString()
    }

    fun mostrar(activity: AppCompatActivity, vm: ContenedorViewModel, lanz: Lanzadores, n: Nodo) {
        val cont = vm.contenedor ?: return
        val raiz = Ui.vertical(activity, 16)
        val editable = EdicionZip.editable(cont)
        val et = if (cont.esApk) "APK" else "ZIP"

        if (n.esDir || n.entrada == null) {
            raiz.addView(Ui.monoespaciado(activity, "Carpeta: ${n.ruta}\nArchivos: ${n.archivos.size}\nTamaño: ${Formato.bytes(n.tamano)}", 12f))
            lateinit var dlgDir: AlertDialog
            if (editable && n.ruta.isNotEmpty()) {
                raiz.addView(Ui.botonSecundario(activity, "$et: renombrar / mover carpeta…") {
                    dlgDir.dismiss()
                    renombrarCarpeta(activity, vm, n)
                }, Ui.anchoTotal(activity, 8))
                raiz.addView(Ui.botonSecundario(activity, "$et: eliminar carpeta y su contenido") {
                    dlgDir.dismiss()
                    eliminarCarpeta(vm, n)
                })
            }
            dlgDir = AlertDialog.Builder(activity)
                .setTitle(n.nombre)
                .setView(raiz)
                .setPositiveButton("Cerrar", null)
                .create()
            dlgDir.show()
            return
        }
        val e: Entrada = n.entrada
        raiz.addView(Ui.monoespaciado(activity, textoDetalles(e), 12f))
        if (editable) {
            val c = vm.cambiosApk
            val estado = when {
                e.ruta in c.eliminados -> "Marcada para eliminar"
                c.renombres.containsKey(e.ruta) -> "Renombrada a " + c.renombres[e.ruta]
                c.reemplazos.containsKey(e.ruta) -> "Reemplazada (pendiente de guardar)"
                else -> ""
            }
            if (estado.isNotEmpty()) raiz.addView(Ui.texto(activity, "✎ $estado", 13f, true), Ui.anchoTotal(activity, 6))
        }

        lateinit var dialogo: AlertDialog
        raiz.addView(Ui.boton(activity, "Vista previa") {
            dialogo.dismiss()
            DialogoVistaPrevia.mostrar(activity, vm, e)
        }, Ui.anchoTotal(activity, 8))
        raiz.addView(Ui.botonSecundario(activity, "Extraer solo esto") {
            dialogo.dismiss()
            val p = PreferenciasApp(activity)
            DialogoExtraccion.mostrar(activity, vm, lanz, setOf(e.ruta))
            if (p.bool("modo_dev")) Ui.toast(activity, "Extrayendo ${e.ruta}")
        })

        if (esKeystore(e)) {
            raiz.addView(Ui.boton(activity, "Usar como keystore…") {
                dialogo.dismiss()
                usarComoKeystore(activity, vm, e)
            }, Ui.anchoTotal(activity, 4))
        }

        if (e.categoria == Iconos.Categoria.COMPRIMIDO || e.categoria == Iconos.Categoria.APK) {
            raiz.addView(Ui.boton(activity, "Abrir como contenedor") {
                dialogo.dismiss()
                vm.abrirAnidado(activity, e)
            }, Ui.anchoTotal(activity, 4))
        }

        if (editable) {
            raiz.addView(Ui.botonSecundario(activity, "$et: reemplazar con archivo…") {
                dialogo.dismiss()
                lanz.abrirDocumento { uri ->
                    if (uri == null) return@abrirDocumento
                    activity.lifecycleScope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) {
                                val d = File(activity.cacheDir, "cambios_apk")
                                d.mkdirs()
                                val destino = File(d, "r_" + System.currentTimeMillis())
                                val i = activity.contentResolver.openInputStream(uri) ?: throw java.io.IOException("No se pudo abrir")
                                i.use { s -> destino.outputStream().use { o -> s.copyTo(o) } }
                                destino
                            }
                            vm.cambiosApk.eliminados.remove(e.ruta)
                            vm.cambiosApk.reemplazos[e.ruta]?.delete()
                            vm.cambiosApk.reemplazos[e.ruta] = f
                            vm.notificar()
                            Ui.toast(activity, if (cont.esApk) "Reemplazo registrado. Ve a la pestaña APK para reempaquetar y firmar." else "Reemplazo registrado. Pulsa «Cambios» en la pestaña Contenedor para guardar.")
                        } catch (t: Throwable) {
                            Ui.toast(activity, "Error: " + Ui.mensajeError(t))
                        }
                    }
                }
            })
            raiz.addView(Ui.botonSecundario(activity, "$et: editar como texto…") {
                dialogo.dismiss()
                editar(activity, vm, e)
            })
            raiz.addView(Ui.botonSecundario(activity, "$et: renombrar / mover…") {
                dialogo.dismiss()
                renombrarEntrada(activity, vm, e)
            })
            raiz.addView(Ui.botonSecundario(activity, if (e.ruta in vm.cambiosApk.eliminados) "$et: no eliminar" else "$et: eliminar del archivo") {
                dialogo.dismiss()
                if (e.ruta in vm.cambiosApk.eliminados) {
                    vm.cambiosApk.eliminados.remove(e.ruta)
                } else {
                    vm.cambiosApk.eliminados.add(e.ruta)
                    vm.cambiosApk.reemplazos.remove(e.ruta)?.delete()
                    vm.cambiosApk.renombres.remove(e.ruta)
                }
                vm.notificar()
            })
        }

        val sv = ScrollView(activity)
        sv.addView(raiz)
        dialogo = AlertDialog.Builder(activity)
            .setTitle(e.nombre)
            .setView(sv)
            .setNegativeButton("Cerrar", null)
            .create()
        dialogo.show()
    }

    fun editar(activity: AppCompatActivity, vm: ContenedorViewModel, e: Entrada) {
        val cont = vm.contenedor ?: return
        val limite = 512 * 1024
        if (e.tamano > limite) {
            Ui.toast(activity, "Archivo demasiado grande para editar aquí (máx. 512 KB)")
            return
        }
        activity.lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val rep = vm.cambiosApk.reemplazos[e.ruta]
                    if (rep != null) rep.readBytes() else Temporales.leerPrimeros(cont, e, limite)
                }
                val esAxml = ParserAxml.esAxml(bytes)
                val textoInicial: String = if (esAxml) {
                    try {
                        ParserAxml.parsear(bytes).xml
                    } catch (t: Throwable) {
                        Ui.toast(activity, "No se pudo decodificar el XML binario: " + Ui.mensajeError(t))
                        return@launch
                    }
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                val campo = EditText(activity)
                campo.setText(textoInicial)
                campo.typeface = android.graphics.Typeface.MONOSPACE
                campo.textSize = 12f * Ui.escala
                campo.setHorizontallyScrolling(true)
                campo.gravity = android.view.Gravity.TOP
                campo.minLines = 12
                val sv = ScrollView(activity)
                sv.addView(campo)
                AlertDialog.Builder(activity)
                    .setTitle((if (esAxml) "Editar (XML binario) " else "Editar ") + e.nombre)
                    .setView(sv)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Guardar cambio") { _, _ ->
                        try {
                            val d = File(activity.cacheDir, "cambios_apk")
                            d.mkdirs()
                            val f = File(d, "t_" + System.currentTimeMillis())
                            val textoNuevo = campo.text.toString()
                            if (esAxml) {
                                f.writeBytes(EscritorAxml.compilar(textoNuevo, bytes))
                            } else {
                                f.writeBytes(textoNuevo.toByteArray(Charsets.UTF_8))
                            }
                            vm.cambiosApk.reemplazos[e.ruta]?.delete()
                            vm.cambiosApk.eliminados.remove(e.ruta)
                            vm.cambiosApk.reemplazos[e.ruta] = f
                            vm.notificar()
                            Ui.toast(activity, if (cont.esApk) "Cambio registrado. Reempaqueta desde la pestaña APK." else "Cambio registrado. Pulsa «Cambios» en la pestaña Contenedor para guardar.")
                        } catch (t: Throwable) {
                            AlertDialog.Builder(activity)
                                .setTitle("No se pudo guardar el cambio")
                                .setMessage(Ui.mensajeError(t))
                                .setPositiveButton("Cerrar", null)
                                .show()
                        }
                    }
                    .show()
            } catch (t: Throwable) {
                Ui.toast(activity, "Error: " + Ui.mensajeError(t))
            }
        }
    }

    // ---------- edición de ZIP: renombrar / eliminar carpetas ----------
    private fun norm(r: String): String = r.replace('\\', '/').trim('/')

    private fun pedirTexto(activity: AppCompatActivity, titulo: String, inicial: String, cb: (String) -> Unit) {
        val campo = EditText(activity)
        campo.setText(inicial)
        campo.setSingleLine(true)
        val cont = Ui.vertical(activity, 16)
        cont.addView(campo)
        AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setView(cont)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aceptar") { _, _ -> cb(campo.text.toString().trim()) }
            .show()
    }

    private fun ocupado(vm: ContenedorViewModel, nuevo: String, propias: Set<String>): Boolean {
        val c = vm.contenedor ?: return false
        val cambios = vm.cambiosApk
        if (c.entradas.any { norm(it.ruta) == nuevo && it.ruta !in propias && it.ruta !in cambios.eliminados }) return true
        if (cambios.renombres.entries.any { norm(it.value) == nuevo && it.key !in propias }) return true
        if (cambios.reemplazos.keys.any { it == nuevo && it !in propias }) return true
        return false
    }

    private fun entradasDe(vm: ContenedorViewModel, ruta: String): List<Entrada> {
        val c = vm.contenedor ?: return emptyList()
        return c.entradas.filter {
            val r = norm(it.ruta)
            r == ruta || r.startsWith("$ruta/")
        }
    }

    private fun renombrarEntrada(activity: AppCompatActivity, vm: ContenedorViewModel, e: Entrada) {
        val actual = vm.cambiosApk.renombres[e.ruta] ?: e.ruta
        pedirTexto(activity, "Renombrar o mover", actual) { texto ->
            val nuevo = Seguridad.limpiarRuta(texto)
            if (nuevo == null) {
                Ui.toast(activity, "Nombre no válido (no puede estar vacío, ser absoluto ni contener «..»)")
                return@pedirTexto
            }
            if (nuevo == norm(e.ruta)) {
                vm.cambiosApk.renombres.remove(e.ruta)
                vm.notificar()
                return@pedirTexto
            }
            if (ocupado(vm, nuevo, setOf(e.ruta))) {
                Ui.toast(activity, "Ya existe una entrada con ese nombre")
                return@pedirTexto
            }
            vm.cambiosApk.renombres[e.ruta] = nuevo
            vm.cambiosApk.eliminados.remove(e.ruta)
            vm.notificar()
            Ui.toast(activity, "Renombrado (pendiente de guardar)")
        }
    }

    private fun renombrarCarpeta(activity: AppCompatActivity, vm: ContenedorViewModel, n: Nodo) {
        pedirTexto(activity, "Renombrar o mover carpeta", n.ruta) { texto ->
            val nuevo = Seguridad.limpiarRuta(texto)
            if (nuevo == null) {
                Ui.toast(activity, "Nombre no válido (no puede estar vacío, ser absoluto ni contener «..»)")
                return@pedirTexto
            }
            if (nuevo == n.ruta) return@pedirTexto
            if (nuevo.startsWith(n.ruta + "/")) {
                Ui.toast(activity, "No puedes mover una carpeta dentro de sí misma")
                return@pedirTexto
            }
            val afectadas = entradasDe(vm, n.ruta)
            val mapa = LinkedHashMap<String, String>()
            for (e in afectadas) {
                val resto = norm(e.ruta).substring(n.ruta.length)
                mapa[e.ruta] = nuevo + resto + (if (e.ruta.endsWith("/")) "/" else "")
            }
            val propias = afectadas.map { it.ruta }.toSet()
            if (mapa.values.any { ocupado(vm, norm(it), propias) }) {
                Ui.toast(activity, "Ya existen entradas con esos nombres")
                return@pedirTexto
            }
            for ((origen, destino) in mapa) {
                vm.cambiosApk.renombres[origen] = destino
                vm.cambiosApk.eliminados.remove(origen)
            }
            vm.notificar()
            Ui.toast(activity, "${mapa.size} entradas renombradas (pendiente de guardar)")
        }
    }

    private fun eliminarCarpeta(vm: ContenedorViewModel, n: Nodo) {
        for (e in entradasDe(vm, n.ruta)) {
            vm.cambiosApk.eliminados.add(e.ruta)
            vm.cambiosApk.reemplazos.remove(e.ruta)?.delete()
            vm.cambiosApk.renombres.remove(e.ruta)
        }
        vm.notificar()
    }

    // ---------- keystores dentro de un contenedor ----------
    private fun esKeystore(e: Entrada): Boolean {
        val ext = Iconos.extension(e.nombre)
        return ext in setOf("keystore", "jks", "p12", "pfx", "bks") || e.nombre.lowercase().contains("keystore")
    }

    private fun usarComoKeystore(activity: AppCompatActivity, vm: ContenedorViewModel, e: Entrada) {
        val cont = vm.contenedor ?: return
        if (e.tamano > 5L * 1024L * 1024L) {
            Ui.toast(activity, "Ese archivo es demasiado grande para ser un keystore")
            return
        }
        activity.lifecycleScope.launch {
            try {
                val tmp = withContext(Dispatchers.IO) {
                    val dir = File(activity.cacheDir, "ks_tmp")
                    dir.mkdirs()
                    val f = File(dir, "ks_" + System.currentTimeMillis())
                    cont.abrirEntrada(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    f
                }
                ImportadorKeystore.iniciar(activity, vm, tmp, e.nombre) { }
            } catch (t: Throwable) {
                Ui.toast(activity, "No se pudo leer el keystore: " + Ui.mensajeError(t))
            }
        }
    }
}
