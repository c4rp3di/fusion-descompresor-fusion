package com.carpe.descompresor.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.carpe.descompresor.util.PreferenciasApp

/** Todos los lanzadores se registran en onCreate (antes de STARTED). */
class Lanzadores(private val activity: ComponentActivity) {
    private var cbSelector: ((List<Uri>) -> Unit)? = null
    private var cbCarpeta: ((Uri?) -> Unit)? = null
    private var cbCrear: ((Uri?) -> Unit)? = null

    /** Selector de archivos: el del sistema (OPEN_DOCUMENT) o un chooser con todas las apps (GET_CONTENT). */
    private val selector = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = cbSelector
        cbSelector = null
        val uris = if (res.resultCode == Activity.RESULT_OK) extraerUris(res.data) else emptyList()
        cb?.invoke(uris)
    }
    private val carpeta = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        cbCarpeta?.invoke(uri)
    }
    private val crear = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        cbCrear?.invoke(uri)
    }
    private val permisoNotif = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val permisoEscritura = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Permiso persistente de lectura: permite reabrir desde "Recientes" y reanudar extracciones. */
    private fun persistirLectura(uri: Uri) {
        try {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // algunos proveedores no lo permiten
        }
    }

    private fun extraerUris(d: Intent?): List<Uri> {
        if (d == null) return emptyList()
        val l = ArrayList<Uri>()
        val clip = d.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val u = clip.getItemAt(i).uri
                if (u != null) l.add(u)
            }
        } else {
            val u = d.data
            if (u != null) l.add(u)
        }
        return l
    }

    private fun lanzarSelector(multiple: Boolean, forzarElegir: Boolean, cb: (List<Uri>) -> Unit) {
        val elegir = forzarElegir || PreferenciasApp(activity).cadena("selector_archivos") == "elegir"
        val base = Intent(if (elegir) Intent.ACTION_GET_CONTENT else Intent.ACTION_OPEN_DOCUMENT)
        base.addCategory(Intent.CATEGORY_OPENABLE)
        base.type = "*/*"
        if (multiple) base.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val intent = if (elegir) Intent.createChooser(base, "Elegir archivo con…") else base
        cbSelector = cb
        try {
            selector.launch(intent)
        } catch (e: Exception) {
            cbSelector = null
            cb(emptyList())
        }
    }

    /** [forzarElegir]: muestra siempre el chooser de apps, aunque el ajuste sea "selector del sistema". */
    fun abrirDocumento(forzarElegir: Boolean = false, cb: (Uri?) -> Unit) {
        lanzarSelector(false, forzarElegir) { l ->
            val u = l.firstOrNull()
            if (u != null) persistirLectura(u)
            cb(u)
        }
    }

    fun elegirVarios(forzarElegir: Boolean = false, cb: (List<Uri>) -> Unit) {
        lanzarSelector(true, forzarElegir) { l ->
            for (u in l) persistirLectura(u)
            cb(l)
        }
    }

    fun elegirCarpeta(cb: (Uri?) -> Unit) {
        cbCarpeta = { uri ->
            if (uri != null) {
                try {
                    activity.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // algunos proveedores no permiten permiso persistente
                }
            }
            cb(uri)
        }
        carpeta.launch(null)
    }

    fun crearDocumento(nombre: String, cb: (Uri?) -> Unit) {
        cbCrear = cb
        crear.launch(nombre)
    }

    fun pedirNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permisoNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun pedirEscrituraLegacy() {
        permisoEscritura.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
