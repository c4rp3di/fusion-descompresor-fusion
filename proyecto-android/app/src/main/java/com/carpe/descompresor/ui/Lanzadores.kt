package com.carpe.descompresor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Todos los lanzadores se registran en onCreate (antes de STARTED). */
class Lanzadores(private val activity: ComponentActivity) {
    private var cbAbrir: ((Uri?) -> Unit)? = null
    private var cbVarios: ((List<Uri>) -> Unit)? = null
    private var cbCarpeta: ((Uri?) -> Unit)? = null
    private var cbCrear: ((Uri?) -> Unit)? = null

    private val abrir = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) persistirLectura(uri)
        cbAbrir?.invoke(uri)
    }
    private val varios = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val l: List<Uri> = uris ?: emptyList()
        for (u in l) persistirLectura(u)
        cbVarios?.invoke(l)
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

    fun abrirDocumento(cb: (Uri?) -> Unit) {
        cbAbrir = cb
        abrir.launch(arrayOf("*/*"))
    }

    fun elegirVarios(cb: (List<Uri>) -> Unit) {
        cbVarios = cb
        varios.launch(arrayOf("*/*"))
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
