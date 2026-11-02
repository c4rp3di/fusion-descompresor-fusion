package com.carpe.descompresor.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.previsualizacion.CatalogoPrevisualizadores
import com.carpe.descompresor.previsualizacion.Previsualizacion
import com.carpe.descompresor.previsualizacion.Temporales
import com.carpe.descompresor.util.PreferenciasApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DialogoVistaPrevia {

    fun mostrar(activity: AppCompatActivity, vm: ContenedorViewModel, entrada: Entrada) {
        val prefs = PreferenciasApp(activity)
        val cont = vm.contenedor ?: return
        if (!prefs.bool("prev_habilitada")) {
            Ui.toast(activity, "La vista previa está desactivada en Ajustes (dispositivo con poca RAM).")
            return
        }
        val marco = FrameLayout(activity)
        val p = Ui.dp(activity, 16)
        marco.setPadding(p, p, p, p)
        val cargando = ProgressBar(activity)
        marco.addView(cargando)
        val limpiezas = ArrayList<() -> Unit>()

        val dialogo = AlertDialog.Builder(activity)
            .setTitle(entrada.nombre)
            .setView(marco)
            .setPositiveButton("Cerrar", null)
            .create()
        dialogo.setOnDismissListener {
            for (l in limpiezas) {
                try { l() } catch (e: Exception) { }
            }
            Temporales.limpiar(activity)
        }
        dialogo.show()

        activity.lifecycleScope.launch {
            val r: Previsualizacion = try {
                withContext(Dispatchers.IO) { CatalogoPrevisualizadores.generar(activity, cont, entrada) }
            } catch (t: Throwable) {
                Previsualizacion.NoSoportada(Ui.mensajeError(t))
            }
            marco.removeAllViews()
            marco.addView(vistaPara(activity, r, prefs, limpiezas))
        }
    }

    private fun vistaPara(activity: AppCompatActivity, r: Previsualizacion, prefs: PreferenciasApp, limpiezas: MutableList<() -> Unit>): View {
        return when (r) {
            is Previsualizacion.Texto -> {
                val l = Ui.vertical(activity, 0)
                l.addView(Ui.texto(activity, r.codificacion + if (r.truncado) " · (truncado al límite configurado)" else "", 11f))
                l.addView(Ui.monoespaciado(activity, r.texto, 12f))
                conScroll(activity, l)
            }
            is Previsualizacion.Hex -> {
                val l = Ui.vertical(activity, 0)
                l.addView(Ui.texto(activity, "Volcado hexadecimal" + if (r.truncado) " (primeros 4 KB)" else "", 11f))
                l.addView(Ui.monoespaciado(activity, r.volcado, 10f))
                conScroll(activity, l)
            }
            is Previsualizacion.Imagen -> {
                val l = Ui.vertical(activity, 0)
                l.addView(Ui.texto(activity, r.info, 11f))
                val iv = ImageView(activity)
                iv.adjustViewBounds = true
                iv.setImageBitmap(r.bitmap)
                l.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                limpiezas.add { }
                conScroll(activity, l)
            }
            is Previsualizacion.Html -> {
                val web = WebView(activity)
                val s = web.settings
                s.javaScriptEnabled = prefs.bool("prev_html_scripts")
                s.allowFileAccess = false
                s.allowContentAccess = false
                s.blockNetworkLoads = true
                web.loadDataWithBaseURL(null, r.html, "text/html", "UTF-8", null)
                web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 420))
                limpiezas.add { web.destroy() }
                web
            }
            is Previsualizacion.Pdf -> vistaPdf(activity, r, limpiezas)
            is Previsualizacion.Medio -> {
                val l = Ui.vertical(activity, 0)
                val vv = VideoView(activity)
                val mc = MediaController(activity)
                mc.setAnchorView(vv)
                vv.setMediaController(mc)
                vv.setVideoURI(Uri.fromFile(r.archivo))
                vv.setOnPreparedListener { vv.start() }
                l.addView(vv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 280)))
                limpiezas.add {
                    vv.stopPlayback()
                    r.archivo.delete()
                }
                l
            }
            is Previsualizacion.NoSoportada -> Ui.texto(activity, r.motivo, 14f)
        }
    }

    private fun conScroll(activity: AppCompatActivity, contenido: View): View {
        val sv = ScrollView(activity)
        sv.addView(contenido)
        return sv
    }

    private fun vistaPdf(activity: AppCompatActivity, r: Previsualizacion.Pdf, limpiezas: MutableList<() -> Unit>): View {
        val l = Ui.vertical(activity, 0)
        val pfd = ParcelFileDescriptor.open(r.archivo, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: Exception) {
            pfd.close()
            return Ui.texto(activity, "No se pudo abrir el PDF: " + (e.message ?: ""), 14f)
        }
        limpiezas.add {
            renderer.close()
            pfd.close()
            r.archivo.delete()
        }
        val iv = ImageView(activity)
        iv.adjustViewBounds = true
        val etiqueta = TextView(activity)
        var pagina = 0
        fun pintar() {
            val pg = renderer.openPage(pagina)
            val w = 1000
            val h = (pg.height.toFloat() * w / pg.width.toFloat()).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            pg.close()
            iv.setImageBitmap(bmp)
            etiqueta.text = "Página ${pagina + 1} de ${renderer.pageCount}"
        }
        val fila = Ui.fila(l.context)
        val anterior = Ui.botonSecundario(activity, "◀") {
            if (pagina > 0) {
                pagina--
                pintar()
            }
        }
        val siguiente = Ui.botonSecundario(activity, "▶") {
            if (pagina < renderer.pageCount - 1) {
                pagina++
                pintar()
            }
        }
        fila.addView(anterior)
        fila.addView(etiqueta)
        fila.addView(siguiente)
        l.addView(fila)
        l.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (renderer.pageCount > 0) pintar()
        return conScroll(activity, l)
    }
}
