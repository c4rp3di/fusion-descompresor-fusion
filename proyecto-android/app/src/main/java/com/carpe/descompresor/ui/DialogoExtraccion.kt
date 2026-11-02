package com.carpe.descompresor.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.carpe.descompresor.crear.CreadorZip
import com.carpe.descompresor.destino.AlmacenamientoDestino
import com.carpe.descompresor.destino.CatalogoDestinos
import com.carpe.descompresor.extraer.DatosPendiente
import com.carpe.descompresor.extraer.EstadoExtraccion
import com.carpe.descompresor.extraer.InformeExtraccion
import com.carpe.descompresor.extraer.OpcionesExtraccion
import com.carpe.descompresor.extraer.Pendiente
import com.carpe.descompresor.extraer.Progreso
import com.carpe.descompresor.extraer.ServicioExtraccion
import com.carpe.descompresor.extraer.SiExiste
import com.carpe.descompresor.extraer.SolicitudExtraccion
import com.carpe.descompresor.util.Formato
import com.carpe.descompresor.util.PreferenciasApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object DialogoExtraccion {

    private fun safListo(activity: AppCompatActivity, prefs: PreferenciasApp): Boolean {
        val s = prefs.safUri()
        if (s.isEmpty()) return false
        return activity.contentResolver.persistedUriPermissions.any { it.uri.toString() == s && it.isWritePermission }
    }

    fun mostrar(activity: AppCompatActivity, vm: ContenedorViewModel, lanz: Lanzadores, rutas: Set<String>, lote: List<Uri>? = null) {
        val prefs = PreferenciasApp(activity)
        val cont = vm.contenedor
        if (lote == null && cont == null) return

        val raiz = Ui.vertical(activity, 16)
        raiz.addView(
            Ui.texto(
                activity,
                if (lote != null) "${lote.size} archivo(s) comprimido(s) · cada uno en su propia carpeta"
                else "${rutas.size} archivo(s) de «${cont?.nombre}»",
                14f, true
            )
        )
        raiz.addView(Ui.texto(activity, "Destino", 13f, true), Ui.anchoTotal(activity, 10))

        val grupo = RadioGroup(activity)
        val rSaf = RadioButton(activity)
        rSaf.text = "Carpeta elegida (SAF, sin permisos especiales)"
        rSaf.id = View.generateViewId()
        val rAlm = RadioButton(activity)
        rAlm.text = "Almacenamiento (acceso total a archivos)"
        rAlm.id = View.generateViewId()
        val rInt = RadioButton(activity)
        rInt.text = "Interno de la app (para extraer y compartir)"
        rInt.id = View.generateViewId()
        grupo.addView(rSaf)
        grupo.addView(rAlm)
        grupo.addView(rInt)
        raiz.addView(grupo)

        val textoSaf = Ui.texto(activity, "", 12f)
        val bCarpeta = Ui.botonSecundario(activity, "Elegir carpeta…") {
            lanz.elegirCarpeta { uri ->
                if (uri != null) {
                    prefs.ponerSafUri(uri.toString())
                    textoSaf.text = "Carpeta: " + (uri.lastPathSegment ?: uri.toString())
                }
            }
        }
        raiz.addView(bCarpeta)
        raiz.addView(textoSaf)
        if (safListo(activity, prefs)) {
            textoSaf.text = "Carpeta: " + (Uri.parse(prefs.safUri()).lastPathSegment ?: prefs.safUri())
        }

        val campoRuta = EditText(activity)
        campoRuta.hint = AlmacenamientoDestino.carpetaPorDefecto().absolutePath
        campoRuta.setText(prefs.cadena("ruta_almacenamiento"))
        campoRuta.setSingleLine(true)
        raiz.addView(campoRuta)

        val inicial = CatalogoDestinos.mejorPorDefecto(activity, prefs.cadena("destino_defecto"))
        when (inicial) {
            "almacenamiento" -> rAlm.isChecked = true
            "interno" -> rInt.isChecked = true
            else -> rSaf.isChecked = true
        }
        fun actualizarVisibilidad() {
            bCarpeta.visibility = if (rSaf.isChecked) View.VISIBLE else View.GONE
            textoSaf.visibility = if (rSaf.isChecked) View.VISIBLE else View.GONE
            campoRuta.visibility = if (rAlm.isChecked) View.VISIBLE else View.GONE
        }
        actualizarVisibilidad()
        grupo.setOnCheckedChangeListener { _, _ -> actualizarVisibilidad() }

        raiz.addView(Ui.texto(activity, "Opciones", 13f, true), Ui.anchoTotal(activity, 10))
        val cPreservar = CheckBox(activity)
        cPreservar.text = "Conservar estructura de carpetas"
        cPreservar.isChecked = prefs.bool("preservar")
        raiz.addView(cPreservar)
        val cCrc = CheckBox(activity)
        cCrc.text = "Verificar CRC"
        cCrc.isChecked = prefs.bool("verificar_crc")
        raiz.addView(cCrc)
        raiz.addView(Ui.texto(activity, "Si el archivo ya existe:", 13f))
        val spExiste = Spinner(activity)
        val nombresExiste = listOf("Renombrar con (1)", "Sobrescribir", "Omitir")
        spExiste.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, nombresExiste)
        spExiste.setSelection(when (prefs.cadena("si_existe")) { "sobrescribir" -> 1; "omitir" -> 2; else -> 0 })
        raiz.addView(spExiste)

        val sv = android.widget.ScrollView(activity)
        sv.addView(raiz)

        AlertDialog.Builder(activity)
            .setTitle("Extraer")
            .setView(sv)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Iniciar") { _, _ ->
                val id = when {
                    rAlm.isChecked -> "almacenamiento"
                    rInt.isChecked -> "interno"
                    else -> "saf"
                }
                val siExiste = when (spExiste.selectedItemPosition) {
                    1 -> SiExiste.SOBRESCRIBIR
                    2 -> SiExiste.OMITIR
                    else -> SiExiste.RENOMBRAR
                }
                prefs.poner("preservar", cPreservar.isChecked)
                prefs.poner("verificar_crc", cCrc.isChecked)
                prefs.poner("si_existe", when (siExiste) { SiExiste.SOBRESCRIBIR -> "sobrescribir"; SiExiste.OMITIR -> "omitir"; else -> "renombrar" })
                val opciones = OpcionesExtraccion(cPreservar.isChecked, siExiste, cCrc.isChecked)
                fun sol(destinoId: String) = SolicitudExtraccion(
                    cont, rutas, destinoId, opciones, lote, if (lote == null) vm.origenReanudable else null
                )
                when (id) {
                    "saf" -> {
                        if (!safListo(activity, prefs)) {
                            lanz.elegirCarpeta { uri ->
                                if (uri != null) {
                                    prefs.ponerSafUri(uri.toString())
                                    lanzar(activity, vm, lanz, sol("saf"))
                                }
                            }
                        } else {
                            lanzar(activity, vm, lanz, sol("saf"))
                        }
                    }
                    "almacenamiento" -> {
                        if (!AlmacenamientoDestino.permisoConcedido(activity)) {
                            pedirPermisoAlmacenamiento(activity, lanz)
                        } else {
                            prefs.poner("ruta_almacenamiento", campoRuta.text.toString().trim())
                            lanzar(activity, vm, lanz, sol("almacenamiento"))
                        }
                    }
                    else -> lanzar(activity, vm, lanz, sol("interno"))
                }
            }
            .show()
    }

    private fun pedirPermisoAlmacenamiento(activity: AppCompatActivity, lanz: Lanzadores) {
        Ui.toast(activity, "Concede el acceso a todos los archivos y vuelve a pulsar «Iniciar».")
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + activity.packageName))
                )
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else if (Build.VERSION.SDK_INT <= 28) {
            lanz.pedirEscrituraLegacy()
        } else {
            Ui.toast(activity, "En Android 10 usa «Carpeta elegida (SAF)» o «Interno».")
        }
    }

    private fun lanzar(activity: AppCompatActivity, vm: ContenedorViewModel, lanz: Lanzadores, sol: SolicitudExtraccion) {
        PreferenciasApp(activity).poner("destino_defecto", sol.destinoId)
        EstadoExtraccion.solicitud = sol
        EstadoExtraccion.cancelar = false
        EstadoExtraccion.estado.value = Progreso(activo = true, mensaje = "Preparando…")
        lanz.pedirNotificaciones()
        ServicioExtraccion.iniciar(activity)
        mostrarProgreso(activity, vm, lanz)
    }

    private fun mostrarProgreso(activity: AppCompatActivity, vm: ContenedorViewModel, lanz: Lanzadores) {
        val raiz = Ui.vertical(activity, 16)
        val barra = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        barra.isIndeterminate = true
        val texto = Ui.texto(activity, "Preparando…", 13f)
        val detalle = Ui.texto(activity, "", 12f)
        raiz.addView(barra)
        raiz.addView(texto, Ui.anchoTotal(activity, 8))
        raiz.addView(detalle)

        val dialogo = AlertDialog.Builder(activity)
            .setTitle("Extrayendo")
            .setView(raiz)
            .setCancelable(false)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Ocultar", null)
            .create()
        dialogo.show()
        dialogo.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            EstadoExtraccion.cancelar = true
            texto.text = "Cancelando…"
        }
        dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialogo.dismiss()
            Ui.toast(activity, "La extracción continúa en segundo plano (mira la notificación).")
        }

        val job: Job = activity.lifecycleScope.launch {
            EstadoExtraccion.estado.collect { p ->
                if (p.activo) {
                    if (p.total > 0) {
                        barra.isIndeterminate = false
                        barra.max = p.total
                        barra.progress = p.hechos
                    }
                    texto.text = "${p.hechos}/${p.total} archivos · ${Formato.bytes(p.bytes)} de ${Formato.bytes(p.bytesTotal)}"
                    detalle.text = p.archivoActual
                } else if (p.informe != null || p.mensaje.startsWith("Error")) {
                    if (dialogo.isShowing) dialogo.dismiss()
                    mostrarInforme(activity, vm, lanz, p)
                    throw kotlinx.coroutines.CancellationException("fin")
                }
            }
        }
        dialogo.setOnDismissListener { job.cancel() }
    }

    fun mostrarInforme(activity: AppCompatActivity, vm: ContenedorViewModel, lanz: Lanzadores, p: Progreso) {
        val inf: InformeExtraccion? = p.informe
        val b = AlertDialog.Builder(activity)
        if (inf == null) {
            b.setTitle("Error").setMessage(p.mensaje).setPositiveButton("Cerrar", null).show()
            return
        }
        val tv = Ui.monoespaciado(activity, inf.detalle(), 12f)
        val sv = android.widget.ScrollView(activity)
        val pad = Ui.dp(activity, 16)
        tv.setPadding(pad, pad, pad, pad)
        sv.addView(tv)
        b.setTitle(if (inf.cancelado) "Extracción cancelada" else "Extracción terminada")
            .setView(sv)
            .setPositiveButton("Cerrar", null)
        if (inf.fallidos.isNotEmpty() && inf.permiteReintento) {
            b.setNegativeButton("Reintentar fallidos") { _, _ ->
                val s = EstadoExtraccion.solicitud
                if (s != null && s.contenedor != null) {
                    val rutas = inf.fallidos.map { it.first }.toSet()
                    lanzar(activity, vm, lanz, SolicitudExtraccion(s.contenedor, rutas, s.destinoId, s.opciones, null, s.origen, s.destinoDetalle))
                }
            }
        }
        val carpeta = inf.carpetaLocal
        if (carpeta != null && inf.extraidos.isNotEmpty()) {
            b.setNeutralButton("Compartir") { _, _ -> compartir(activity, carpeta) }
        }
        b.show()
    }

    private fun compartir(activity: AppCompatActivity, carpeta: File) {
        activity.lifecycleScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val ficheros = carpeta.walkTopDown().filter { it.isFile }.toList()
                    if (ficheros.size == 1) {
                        ficheros[0]
                    } else {
                        val d = File(activity.cacheDir, "compartir")
                        d.mkdirs()
                        val z = File(d, "extraccion.zip")
                        CreadorZip.crearDesdeCarpeta(carpeta, z)
                        z
                    }
                }
                val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", f)
                val i = Intent(Intent.ACTION_SEND)
                i.type = "*/*"
                i.putExtra(Intent.EXTRA_STREAM, uri)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.startActivity(Intent.createChooser(i, "Compartir"))
            } catch (t: Throwable) {
                Ui.toast(activity, "No se pudo compartir: " + Ui.mensajeError(t))
            }
        }
    }

    /** Reanuda una extracción interrumpida: reabre el origen y salta lo que ya se extrajo. */
    fun reanudar(activity: AppCompatActivity, vm: ContenedorViewModel, lanz: Lanzadores, d: DatosPendiente) {
        val enCurso = PreferenciasApp(activity).cadena("pendiente_en_curso")
        val opciones = OpcionesExtraccion(d.preservar, SiExiste.OMITIR, d.verificarCrc, "", true, enCurso)
        if (d.tipo == "lote") {
            val uris = d.uris.map { Uri.parse(it) }
            lanzar(activity, vm, lanz, SolicitudExtraccion(null, emptySet(), d.destinoId, opciones, uris, null, d.destinoDetalle))
            return
        }
        vm.abrir(activity, Uri.parse(d.origen), false) { ok ->
            val c = vm.contenedor
            if (!ok || c == null) {
                Ui.toast(activity, "No se pudo reabrir «${d.nombre}»: " + (vm.error ?: "el archivo ya no está accesible"))
                Pendiente.borrar(activity)
            } else {
                lanzar(activity, vm, lanz, SolicitudExtraccion(c, d.rutas.toSet(), d.destinoId, opciones, null, d.origen, d.destinoDetalle))
            }
        }
    }
}
