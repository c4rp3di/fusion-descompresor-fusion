package com.carpe.descompresor.ui

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.carpe.descompresor.util.Formato
import com.carpe.descompresor.util.Iconos

class ArbolAdapter(
    private val ctx: Context,
    private val vm: ContenedorViewModel,
    private val alClicFila: (Nodo) -> Unit
) : RecyclerView.Adapter<ArbolAdapter.VH>() {

    private var items: List<Nodo> = emptyList()
    var resaltar: String = ""
    var modoPlano: Boolean = false

    class VH(
        val fila: LinearLayout,
        val flecha: TextView,
        val check: TextView,
        val icono: TextView,
        val nombre: TextView,
        val tam: TextView
    ) : RecyclerView.ViewHolder(fila)

    fun actualizar(l: List<Nodo>) {
        items = l
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val fila = LinearLayout(ctx)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val v = Ui.dp(ctx, 6)
        fila.setPadding(Ui.dp(ctx, 6), v, Ui.dp(ctx, 8), v)

        val flecha = Ui.texto(ctx, "", 16f)
        flecha.layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 22), ViewGroup.LayoutParams.WRAP_CONTENT)
        val check = Ui.texto(ctx, "☐", 20f)
        check.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 8), 0)
        val icono = Ui.texto(ctx, "", 18f)
        icono.setPadding(0, 0, Ui.dp(ctx, 6), 0)
        val nombre = Ui.texto(ctx, "", 14f)
        nombre.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val tam = Ui.texto(ctx, "", 12f)
        tam.alpha = 0.7f

        fila.addView(flecha)
        fila.addView(check)
        fila.addView(icono)
        fila.addView(nombre)
        fila.addView(tam)
        return VH(fila, flecha, check, icono, nombre, tam)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val n = items[position]
        val nivel = if (modoPlano) 0 else n.nivel
        h.fila.setPadding(Ui.dp(ctx, 6 + nivel * 18), h.fila.paddingTop, h.fila.paddingRight, h.fila.paddingBottom)
        h.flecha.text = if (n.esDir && !modoPlano) (if (n.ruta in vm.expandidos) "▾" else "▸") else ""
        h.check.text = when (vm.estadoSeleccion(n)) {
            2 -> "☑"
            1 -> "◪"
            else -> "☐"
        }
        val cat = n.entrada?.categoria ?: Iconos.Categoria.DESCONOCIDO
        h.icono.text = Iconos.emoji(cat, n.esDir)

        val textoNombre = if (modoPlano) n.ruta else n.nombre
        val s = SpannableString(textoNombre)
        val q = resaltar.trim().lowercase()
        if (q.isNotEmpty()) {
            val bajo = textoNombre.lowercase()
            var i = bajo.indexOf(q)
            while (i >= 0) {
                s.setSpan(BackgroundColorSpan(Color.argb(110, 255, 235, 59)), i, i + q.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = bajo.indexOf(q, i + q.length)
            }
        }
        h.nombre.text = s
        h.tam.text = if (n.esDir) "${n.archivos.size} · ${Formato.bytes(n.tamano)}" else Formato.bytes(n.entrada?.tamano ?: -1L)

        h.check.setOnClickListener { vm.alternar(n) }
        h.fila.setOnClickListener {
            if (n.esDir && !modoPlano) vm.alternarExpandido(n) else alClicFila(n)
        }
    }
}
