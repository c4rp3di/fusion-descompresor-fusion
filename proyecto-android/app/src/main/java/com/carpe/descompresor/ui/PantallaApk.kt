package com.carpe.descompresor.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carpe.descompresor.apk.AlineadorZip
import com.carpe.descompresor.apk.BundleApk
import com.carpe.descompresor.apk.InstaladorApks
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.apk.FirmadorApk
import com.carpe.descompresor.apk.GestorKeystore
import com.carpe.descompresor.apk.InfoFirma
import com.carpe.descompresor.apk.InspectorApk
import com.carpe.descompresor.apk.LectorFirma
import com.carpe.descompresor.apk.Reempaquetador
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.Formato
import com.carpe.descompresor.util.PreferenciasApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class PantallaApk(
    private val activity: AppCompatActivity,
    private val vm: ContenedorViewModel,
    private val lanz: Lanzadores
) {
    private val scroll = ScrollView(activity)
    private val contenido = Ui.vertical(activity, 16)
    val vista: View get() = scroll

    private var manifestCache: InspectorApk.Manifest? = null
    private var cacheDe: Contenedor? = null
    private var cargando = false

    init {
        scroll.addView(contenido)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.revision.collect { construir() }
            }
        }
        construir()
    }

    private fun construir() {
        contenido.removeAllViews()
        val c = vm.contenedor
        if (c != null && BundleApk.esBundle(c)) {
            construirBundle(c)
            return
        }
        if (c == null || !c.esApk) {
            contenido.addView(Ui.texto(activity, "Abre un archivo .apk en la pestaña Contenedor para inspeccionarlo, editarlo, reempaquetarlo, firmarlo e instalarlo. También puedes abrir paquetes .apks, .xapk o .apkm para instalarlos.", 14f))
            return
        }
        contenido.addView(Ui.texto(activity, c.nombre, 16f, true))
        contenido.addView(Ui.texto(activity, Formato.bytes(c.tamanoArchivo) + " · " + vm.totalArchivos() + " archivos", 12f))

        if (cacheDe !== c) {
            manifestCache = null
            cacheDe = c
            cargando = true
            activity.lifecycleScope.launch {
                try {
                    val m = withContext(Dispatchers.IO) { InspectorApk.leer(c.archivo) }
                    manifestCache = m
                } catch (t: Throwable) {
                    Ui.toast(activity, "No se pudo leer el Manifest: " + Ui.mensajeError(t))
                }
                cargando = false
                construir()
            }
        }
        val m = manifestCache
        if (m != null) {
            val md = m.metadatos
            val sb = StringBuilder()
            sb.append("Paquete: ").append(md.paquete).append('\n')
            sb.append("Versión: ").append(md.versionNombre).append(" (código ").append(md.versionCodigo).append(")\n")
            sb.append("SDK mínimo: ").append(md.minSdk).append(" · objetivo: ").append(md.targetSdk).append('\n')
            sb.append("Etiqueta: ").append(md.etiqueta).append('\n')
            sb.append("Permisos: ").append(md.permisos.size).append(" · Componentes: ").append(md.componentes.size)
            contenido.addView(Ui.monoespaciado(activity, sb.toString(), 12f), Ui.anchoTotal(activity, 8))
        } else if (cargando) {
            contenido.addView(ProgressBar(activity))
        }

        contenido.addView(Ui.boton(activity, "Ver Manifest (XML)") { verManifest() }, Ui.anchoTotal(activity, 8))
        contenido.addView(Ui.botonSecundario(activity, "Editar Manifest…") { editarManifest(c) })
        contenido.addView(Ui.botonSecundario(activity, "Permisos y componentes") { verComponentes() })
        contenido.addView(Ui.botonSecundario(activity, "Ver firma") { verFirma(c) })
        contenido.addView(Ui.botonSecundario(activity, "Comprobar alineación (zipalign)") { verAlineacion(c) })
        contenido.addView(Ui.boton(activity, "Instalar este APK (tal cual está)") { instalarOriginal(c) }, Ui.anchoTotal(activity, 8))

        contenido.addView(Ui.texto(activity, "Reempaquetar", 15f, true), Ui.anchoTotal(activity, 16))
        val cambios = vm.cambiosApk
        contenido.addView(
            Ui.texto(
                activity,
                if (cambios.vacio()) "Sin cambios pendientes. Para editar: en la pestaña Contenedor toca un archivo del APK y elige reemplazar, editar o eliminar."
                else "Cambios pendientes: ${cambios.reemplazos.size} reemplazo(s)/alta(s), ${cambios.renombres.size} renombrado(s), ${cambios.eliminados.size} eliminación(es).",
                12f
            )
        )
        contenido.addView(Ui.boton(activity, "Reempaquetar → alinear → firmar") { reempaquetarYFirmar(c) }, Ui.anchoTotal(activity, 8))
        if (!cambios.vacio()) {
            contenido.addView(Ui.botonSecundario(activity, "Descartar cambios") {
                cambios.limpiar()
                vm.notificar()
            })
        }
        contenido.addView(
            Ui.texto(activity, "La firma usa la keystore activa de Ajustes → APK / Firma. Sin keystore no se puede firmar.", 11f),
            Ui.anchoTotal(activity, 8)
        )
    }

    private fun dialogoTexto(titulo: String, texto: String) {
        val tv = Ui.monoespaciado(activity, texto, 11f)
        val p = Ui.dp(activity, 16)
        tv.setPadding(p, p, p, p)
        val sv = ScrollView(activity)
        sv.addView(tv)
        AlertDialog.Builder(activity).setTitle(titulo).setView(sv).setPositiveButton("Cerrar", null).show()
    }

    /** Instala el APK abierto sin tocarlo (los cambios pendientes NO se incluyen). */
    private fun instalarOriginal(c: Contenedor) {
        activity.lifecycleScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val dir = File(activity.cacheDir, "salida_apk")
                    dir.mkdirs()
                    var nombre = c.nombre.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    if (!nombre.endsWith(".apk", ignoreCase = true)) nombre += ".apk"
                    val destino = File(dir, nombre)
                    c.archivo.inputStream().use { i -> destino.outputStream().use { o -> i.copyTo(o) } }
                    destino
                }
                if (!vm.cambiosApk.vacio()) {
                    Ui.toast(activity, "Se instala el APK original: los cambios pendientes no están incluidos.")
                }
                instalar(f)
            } catch (t: Throwable) {
                Ui.toast(activity, "No se pudo preparar la instalación: " + Ui.mensajeError(t))
            }
        }
    }

    private fun verManifest() {
        val m = manifestCache
        if (m == null) {
            Ui.toast(activity, "El Manifest aún no está cargado")
            return
        }
        dialogoTexto("AndroidManifest.xml", m.xml)
    }

    private fun editarManifest(c: Contenedor) {
        val e = c.entradas.firstOrNull { it.ruta == "AndroidManifest.xml" }
        if (e == null) {
            Ui.toast(activity, "Este APK no contiene AndroidManifest.xml")
            return
        }
        PantallaDetalles.editar(activity, vm, e)
    }

    private fun verComponentes() {
        val m = manifestCache ?: return
        val sb = StringBuilder()
        sb.append("Permisos (${m.metadatos.permisos.size})\n")
        for (p in m.metadatos.permisos) sb.append("• ").append(p).append('\n')
        sb.append("\nComponentes (${m.metadatos.componentes.size})\n")
        for (c in m.metadatos.componentes) sb.append("• [").append(c.tipo).append("] ").append(c.nombre).append("  exportado=").append(c.exportado).append('\n')
        dialogoTexto("Permisos y componentes", sb.toString())
    }

    private fun verFirma(c: Contenedor) {
        activity.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { LectorFirma.leer(c.archivo) }
            dialogoTexto("Firma del APK", info.texto())
        }
    }

    private fun verAlineacion(c: Contenedor) {
        activity.lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val malas = AlineadorZip.verificar(c.archivo)
                    if (malas.isEmpty()) "✔ Todas las entradas sin comprimir están alineadas a 4 bytes."
                    else "✘ ${malas.size} entradas sin alinear:\n" + malas.take(30).joinToString("\n") { "• $it" }
                } catch (t: Throwable) {
                    "No se pudo comprobar: " + Ui.mensajeError(t)
                }
            }
            dialogoTexto("Alineación", msg)
        }
    }

    private class ResultadoFirma(val archivo: File, val info: InfoFirma?, val resumen: String)

    private fun reempaquetarYFirmar(c: Contenedor) {
        val prefs = PreferenciasApp(activity)
        val idKs: String? = try {
            GestorKeystore.activa(activity)
        } catch (t: Throwable) {
            Ui.toast(activity, "No se pudo acceder al almacén cifrado: " + Ui.mensajeError(t))
            return
        }
        if (idKs == null) {
            Ui.toast(activity, "Importa y selecciona una keystore en Ajustes → APK / Firma")
            return
        }
        val v1 = prefs.bool("firma_v1")
        val v2 = prefs.bool("firma_v2")
        val v3 = prefs.bool("firma_v3")
        if (!v1 && !v2 && !v3) {
            Ui.toast(activity, "Activa al menos un esquema de firma en Ajustes")
            return
        }
        val espera = AlertDialog.Builder(activity)
            .setTitle("Procesando APK")
            .setMessage("Reempaquetando, alineando y firmando…")
            .setView(ProgressBar(activity))
            .setCancelable(false)
            .create()
        espera.show()

        activity.lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    val dir = File(activity.cacheDir, "salida_apk")
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val base = c.nombre.removeSuffix(".apk").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val paso1 = File(dir, "paso1.apk")
                    val paso2 = File(dir, "paso2.apk")
                    val fin = File(dir, "$base-firmado.apk")

                    Reempaquetador.reconstruir(c.archivo, vm.cambiosApk, paso1)
                    AlineadorZip.alinear(paso1, paso2)
                    if (prefs.bool("verificar_alineacion")) {
                        val malas = AlineadorZip.verificar(paso2)
                        if (malas.isNotEmpty()) throw IOException("Alineación incorrecta en ${malas.size} entradas")
                    }
                    val clave = GestorKeystore.cargarClave(activity, idKs)
                    val minSdk: Int? = try {
                        InspectorApk.minSdkNumerico(InspectorApk.leer(paso2).metadatos)
                    } catch (t: Throwable) {
                        null
                    }
                    FirmadorApk.firmar(paso2, fin, clave, v1, v2, v3, minSdk)
                    paso1.delete()
                    paso2.delete()
                    var info: InfoFirma? = null
                    if (prefs.bool("verificar_tras_firmar")) {
                        info = LectorFirma.leer(fin)
                        if (!info.verificado) throw IOException("La verificación posterior falló:\n" + info.texto())
                    }
                    ResultadoFirma(fin, info, "APK firmado (${Formato.bytes(fin.length())}). Esquemas: v1=$v1 v2=$v2 v3=$v3")
                }
                espera.dismiss()
                mostrarResultado(r)
            } catch (t: Throwable) {
                espera.dismiss()
                dialogoTexto("No se pudo completar", Ui.mensajeError(t))
            }
        }
    }

    private fun mostrarResultado(r: ResultadoFirma) {
        val texto = r.resumen + "\n\n" + (r.info?.texto() ?: "Verificación posterior desactivada.")
        val tv = Ui.monoespaciado(activity, texto, 11f)
        val p = Ui.dp(activity, 16)
        tv.setPadding(p, p, p, p)
        val sv = ScrollView(activity)
        sv.addView(tv)
        AlertDialog.Builder(activity)
            .setTitle("✔ APK listo")
            .setView(sv)
            .setPositiveButton("Instalar") { _, _ -> instalar(r.archivo) }
            .setNegativeButton("Guardar…") { _, _ -> guardar(r.archivo) }
            .setNeutralButton("Compartir") { _, _ -> compartir(r.archivo) }
            .show()
    }

    private fun uriDe(f: File): Uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", f)

    /** Android exige permitir "instalar apps desconocidas" a esta app; si falta, abre el ajuste. */
    private fun permitirInstalacion(): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Ui.toast(activity, "Permite instalar apps desde Descompresor y vuelve a pulsar «Instalar».")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                )
            } catch (t: Throwable) {
                Ui.toast(activity, "Abre Ajustes → Apps → Descompresor → Instalar apps desconocidas")
            }
            return false
        }
        return true
    }

    private fun instalar(f: File) {
        try {
            if (!permitirInstalacion()) return
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uriDe(f), "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(i)
        } catch (t: Throwable) {
            Ui.toast(activity, "No se pudo lanzar la instalación: " + Ui.mensajeError(t))
        }
    }

    // ---------- paquetes de APKs divididos (.apks / .xapk / .apkm) ----------
    private fun construirBundle(c: Contenedor) {
        contenido.addView(Ui.texto(activity, c.nombre, 16f, true))
        contenido.addView(Ui.texto(activity, Formato.bytes(c.tamanoArchivo) + " · paquete de APKs", 12f))
        val plan = BundleApk.plan(activity, c)
        val sb = StringBuilder()
        sb.append("Contiene ${plan.todos.size} APK(s). Para este dispositivo se instalarían ${plan.elegidos.size}.")
        if (plan.universal) sb.append("\nEs un paquete universal: un solo APK.")
        if (plan.hayObb) sb.append("\n⚠ Incluye archivos OBB (datos extra): no se instalan; cópialos a Android/obb/<paquete>/ si la app los necesita.")
        contenido.addView(Ui.texto(activity, sb.toString(), 13f), Ui.anchoTotal(activity, 8))
        contenido.addView(Ui.boton(activity, "Instalar paquete (${plan.elegidos.size} APK)") { confirmarBundle(c, plan.elegidos, plan.todos) }, Ui.anchoTotal(activity, 8))
        contenido.addView(
            Ui.texto(activity, "Se escogen los splits que corresponden a la arquitectura, densidad e idioma de este dispositivo. Si la instalación falla, prueba con «Todos los splits».", 11f),
            Ui.anchoTotal(activity, 8)
        )
    }

    private fun lineasSplits(l: List<Entrada>): String {
        val sb = StringBuilder()
        for (e in l.take(30)) sb.append("• ").append(e.nombre).append(" (").append(Formato.bytes(e.tamano)).append(")\n")
        if (l.size > 30) sb.append("… y ${l.size - 30} más\n")
        return sb.toString()
    }

    private fun confirmarBundle(c: Contenedor, elegidos: List<Entrada>, todos: List<Entrada>) {
        val tv = Ui.monoespaciado(activity, lineasSplits(elegidos), 11f)
        val p = Ui.dp(activity, 16)
        tv.setPadding(p, p, p, p)
        val sv = ScrollView(activity)
        sv.addView(tv)
        AlertDialog.Builder(activity)
            .setTitle("Se instalarán ${elegidos.size} APK")
            .setView(sv)
            .setPositiveButton("Instalar") { _, _ -> instalarBundle(c, elegidos) }
            .setNeutralButton("Todos los splits (${todos.size})") { _, _ -> instalarBundle(c, todos) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun instalarBundle(c: Contenedor, lista: List<Entrada>) {
        if (!permitirInstalacion()) return
        val espera = AlertDialog.Builder(activity)
            .setTitle("Preparando instalación")
            .setMessage("Extrayendo ${lista.size} APK…")
            .setView(ProgressBar(activity))
            .setCancelable(false)
            .create()
        espera.show()
        activity.lifecycleScope.launch {
            var dir: File? = null
            try {
                withContext(Dispatchers.IO) {
                    val d = File(activity.cacheDir, "salida_apk/bundle_" + System.currentTimeMillis())
                    d.mkdirs()
                    dir = d
                    val archivos = ArrayList<File>()
                    for ((i, e) in lista.withIndex()) {
                        val f = File(d, "split_$i.apk")
                        c.abrirEntrada(e).use { ent -> f.outputStream().use { o -> ent.copyTo(o) } }
                        archivos.add(f)
                    }
                    InstaladorApks.instalar(activity, archivos)
                }
                espera.dismiss()
                Ui.toast(activity, "Instalación enviada: confirma en la pantalla del sistema.")
            } catch (t: Throwable) {
                espera.dismiss()
                dialogoTexto("No se pudo instalar el paquete", Ui.mensajeError(t))
            } finally {
                dir?.deleteRecursively()
            }
        }
    }

    private fun compartir(f: File) {
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "application/vnd.android.package-archive"
            i.putExtra(Intent.EXTRA_STREAM, uriDe(f))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(i, "Compartir APK"))
        } catch (t: Throwable) {
            Ui.toast(activity, "No se pudo compartir: " + Ui.mensajeError(t))
        }
    }

    private fun guardar(f: File) {
        lanz.crearDocumento(f.name) { uri ->
            if (uri == null) return@crearDocumento
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val o = activity.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No se pudo escribir")
                        o.use { out -> f.inputStream().use { it.copyTo(out) } }
                    }
                    Ui.toast(activity, "✔ APK guardado")
                } catch (t: Throwable) {
                    Ui.toast(activity, "No se pudo guardar: " + Ui.mensajeError(t))
                }
            }
        }
    }
}
