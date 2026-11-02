package com.carpe.descompresor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import com.carpe.descompresor.extraer.Pendiente
import com.carpe.descompresor.ui.ContenedorViewModel
import com.carpe.descompresor.ui.DialogoExtraccion
import com.carpe.descompresor.ui.Lanzadores
import com.carpe.descompresor.ui.PantallaAjustes
import com.carpe.descompresor.ui.PantallaApk
import com.carpe.descompresor.ui.PantallaLista
import com.carpe.descompresor.ui.Ui
import com.carpe.descompresor.util.ArchivoInbox
import com.carpe.descompresor.util.PreferenciasApp
import com.google.android.material.bottomnavigation.BottomNavigationView

// Punto de entrada de la app: MainActivity llama a onIniciar() al terminar de arrancar.
object AppLogic {
    fun onIniciar(activity: MainActivity) {
        Log.i("Descompresor", "Arranque OK · com.carpe.descompresor")

        val prefs = PreferenciasApp(activity)
        prefs.inicializarSiHaceFalta(activity)
        Ui.escala = prefs.entero("escala_fuente") / 100f
        val modoTema = when (prefs.cadena("tema")) {
            "claro" -> AppCompatDelegate.MODE_NIGHT_NO
            "oscuro" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != modoTema) AppCompatDelegate.setDefaultNightMode(modoTema)

        val vm = ViewModelProvider(activity)[ContenedorViewModel::class.java]
        if (vm.contenedor == null && !vm.cargando) ArchivoInbox.barrerTodo(activity) else ArchivoInbox.barrerAntiguos(activity)

        val lanz = Lanzadores(activity)
        val marco = activity.findViewById<FrameLayout>(R.id.contenedor_principal)
        val nav = activity.findViewById<BottomNavigationView>(R.id.barra_inferior)

        val lista = PantallaLista(activity, vm, lanz)
        val apk = PantallaApk(activity, vm, lanz)
        val ajustes = PantallaAjustes(activity, vm, lanz)

        marco.removeAllViews()
        marco.addView(lista.vista)
        marco.addView(apk.vista)
        marco.addView(ajustes.vista)

        fun mostrar(cual: View) {
            lista.vista.visibility = if (cual === lista.vista) View.VISIBLE else View.GONE
            apk.vista.visibility = if (cual === apk.vista) View.VISIBLE else View.GONE
            ajustes.vista.visibility = if (cual === ajustes.vista) View.VISIBLE else View.GONE
        }
        fun mostrarPorId(id: Int) {
            when (id) {
                R.id.nav_apk -> mostrar(apk.vista)
                R.id.nav_ajustes -> mostrar(ajustes.vista)
                else -> mostrar(lista.vista)
            }
        }

        nav.setOnItemSelectedListener { item ->
            vm.pestana = item.itemId
            mostrarPorId(item.itemId)
            true
        }
        nav.setOnItemReselectedListener { item -> mostrarPorId(item.itemId) }
        // Tras recrear la actividad (cambio de tema, giro) se vuelve a la pestaña en la que estabas.
        val inicial = if (vm.pestana == 0) R.id.nav_contenedor else vm.pestana
        nav.selectedItemId = inicial
        mostrarPorId(inicial)

        // Atrás: pestaña distinta de Contenedor -> vuelve a Contenedor; archivo anidado -> contenedor anterior; si no, sale.
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (nav.selectedItemId != R.id.nav_contenedor) {
                    nav.selectedItemId = R.id.nav_contenedor
                } else if (vm.nivel > 0) {
                    vm.cerrar()
                } else {
                    isEnabled = false
                    activity.onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        if (!vm.pidioNotificaciones) {
            vm.pidioNotificaciones = true
            lanz.pedirNotificaciones()
        }

        // ¿Quedó una extracción a medias (la app o el sistema cerraron el proceso)?
        if (!vm.preguntoPendiente && vm.contenedor == null && !vm.cargando) {
            vm.preguntoPendiente = true
            val d = Pendiente.leer(activity)
            if (d != null) {
                val que = if (d.tipo == "lote") "El lote de ${d.uris.size} archivo(s)"
                else "La extracción de «${d.nombre}» (${d.rutas.size} archivos)"
                AlertDialog.Builder(activity)
                    .setTitle("Extracción interrumpida")
                    .setMessage("$que no llegó a terminar. ¿Reanudarla? Lo ya extraído se conserva y se salta; el archivo que estaba a medias se rehace.")
                    .setPositiveButton("Reanudar") { _, _ -> DialogoExtraccion.reanudar(activity, vm, lanz, d) }
                    .setNegativeButton("Descartar") { _, _ -> Pendiente.borrar(activity) }
                    .setNeutralButton("Más tarde", null)
                    .show()
            }
        }
    }

    /** Extrae la Uri de un intent VIEW (Abrir con) o SEND (Compartir con). */
    private fun uriDe(intent: Intent): Uri? {
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        }
    }

    /** Si el intent trae un archivo de otra app, lo copia a caché y abre su árbol. Si no, no hace nada. */
    fun manejarIntent(activity: MainActivity, intent: Intent?) {
        if (intent == null) return
        val uri = uriDe(intent) ?: return
        Log.i("Descompresor", "Archivo recibido: $uri")
        val vm = ViewModelProvider(activity)[ContenedorViewModel::class.java]
        activity.findViewById<BottomNavigationView>(R.id.barra_inferior).selectedItemId = R.id.nav_contenedor
        vm.abrir(activity, uri, true)
    }
}
