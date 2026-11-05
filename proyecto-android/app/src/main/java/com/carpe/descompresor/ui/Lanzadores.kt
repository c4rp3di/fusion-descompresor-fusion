package com.carpe.descompresor.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
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

    private class OpcionApp(val titulo: String, val detalle: String, val icono: Drawable?, val info: ResolveInfo?)

    private class AdaptadorApps(private val ctx: Context, private val items: List<OpcionApp>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val o = items[position]
            val fila = LinearLayout(ctx)
            fila.orientation = LinearLayout.HORIZONTAL
            fila.gravity = android.view.Gravity.CENTER_VERTICAL
            val p = Ui.dp(ctx, 14)
            fila.setPadding(p, Ui.dp(ctx, 10), p, Ui.dp(ctx, 10))
            val icono = ImageView(ctx)
            icono.setImageDrawable(o.icono)
            fila.addView(icono, LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 40)))
            val textos = LinearLayout(ctx)
            textos.orientation = LinearLayout.VERTICAL
            textos.setPadding(Ui.dp(ctx, 12), 0, 0, 0)
            textos.addView(Ui.texto(ctx, o.titulo, 15f))
            val sub = Ui.texto(ctx, o.detalle, 11f)
            sub.alpha = 0.6f
            textos.addView(sub)
            fila.addView(textos)
            return fila
        }
    }

    @Suppress("DEPRECATION")
    private fun appsSelectoras(base: Intent): List<ResolveInfo> {
        val pm = activity.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(base, PackageManager.ResolveInfoFlags.of(0L))
            else pm.queryIntentActivities(base, 0)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun lanzarSelector(multiple: Boolean, forzarElegir: Boolean, cb: (List<Uri>) -> Unit) {
        val prefs = PreferenciasApp(activity)
        val modo = prefs.cadena("selector_archivos")   // sistema | elegir | app

        fun preparar(accion: String): Intent {
            val i = Intent(accion)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            return i
        }

        fun lanzar(intent: Intent): Boolean {
            cbSelector = cb
            return try {
                selector.launch(intent)
                true
            } catch (e: Exception) {
                cbSelector = null
                false
            }
        }

        fun lanzarSistema() {
            if (!lanzar(preparar(Intent.ACTION_OPEN_DOCUMENT))) cb(emptyList())
        }

        if (!forzarElegir && modo == "sistema") {
            lanzarSistema()
            return
        }

        // Apps capaces de elegir archivos (lista propia: el chooser de Android se salta en algunos móviles).
        val base = preparar(Intent.ACTION_GET_CONTENT)
        val pm = activity.packageManager
        val apps = ArrayList<ResolveInfo>()
        val vistos = HashSet<String>()
        for (ri in appsSelectoras(base)) {
            val ai = ri.activityInfo ?: continue
            if (!ai.exported) continue
            if (!vistos.add(ai.packageName + "/" + ai.name)) continue
            apps.add(ri)
        }

        // Modo "usar la app recordada": se abre directamente.
        if (!forzarElegir && modo == "app") {
            val guardada = prefs.cadena("selector_app")
            val ri = apps.firstOrNull { it.activityInfo.packageName + "/" + it.activityInfo.name == guardada }
            if (ri != null) {
                val intent = preparar(Intent.ACTION_GET_CONTENT)
                intent.setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                if (lanzar(intent)) return
                Ui.toast(activity, "No se pudo abrir la app elegida; se usa el selector del sistema.")
                lanzarSistema()
                return
            }
            // aún no hay app recordada (o se desinstaló): se muestra la lista
        }

        // Se recuerda la elección si viene de pulsación larga o del modo "usar la app que elija".
        val recordar = forzarElegir || modo == "app"
        val opciones = ArrayList<OpcionApp>()
        opciones.add(OpcionApp("Selector del sistema", "Recomendado: permite «Recientes» y «Reanudar»", null, null))
        for (ri in apps) {
            opciones.add(OpcionApp(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, ri.loadIcon(pm), ri))
        }
        AlertDialog.Builder(activity)
            .setTitle("Elegir archivo con…")
            .setAdapter(AdaptadorApps(activity, opciones)) { _, i ->
                val ri = opciones[i].info
                if (ri == null) {
                    if (recordar) prefs.poner("selector_archivos", "sistema")
                    lanzarSistema()
                } else {
                    if (recordar) {
                        prefs.poner("selector_archivos", "app")
                        prefs.poner("selector_app", ri.activityInfo.packageName + "/" + ri.activityInfo.name)
                        prefs.poner("selector_app_nombre", opciones[i].titulo)
                        Ui.toast(activity, "Selector recordado: ${opciones[i].titulo}. Mantén pulsado «Abrir archivo» para cambiarlo.")
                    }
                    val intent = preparar(Intent.ACTION_GET_CONTENT)
                    intent.setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    if (!lanzar(intent)) {
                        Ui.toast(activity, "No se pudo abrir esa app; se usa el selector del sistema.")
                        lanzarSistema()
                    }
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> cb(emptyList()) }
            .show()
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
