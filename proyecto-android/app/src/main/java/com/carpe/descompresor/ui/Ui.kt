package com.carpe.descompresor.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

object Ui {
    var escala: Float = 1.0f

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun texto(ctx: Context, s: CharSequence, tam: Float = 14f, negrita: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = tam * escala
        if (negrita) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    fun monoespaciado(ctx: Context, s: CharSequence, tam: Float = 12f): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = tam * escala
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        return t
    }

    fun boton(ctx: Context, s: String, alClic: () -> Unit): MaterialButton {
        val b = MaterialButton(ctx)
        b.text = s
        b.isAllCaps = false
        b.textSize = 13f * escala
        b.setOnClickListener { alClic() }
        return b
    }

    fun botonSecundario(ctx: Context, s: String, alClic: () -> Unit): MaterialButton {
        val b = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = s
        b.isAllCaps = false
        b.textSize = 13f * escala
        b.setOnClickListener { alClic() }
        return b
    }

    fun vertical(ctx: Context, padding: Int = 12): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padding)
        l.setPadding(p, p, p, p)
        return l
    }

    fun fila(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun pesoUno(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    fun anchoTotal(ctx: Context, arriba: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(ctx, arriba)
        return lp
    }

    fun separador(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(0x33808080)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
        return v
    }

    fun toast(ctx: Context, s: String) {
        Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    }

    fun mensajeError(t: Throwable): String {
        val nombre = t.javaClass.simpleName
        if (nombre.contains("UnsupportedRarV5")) return "RAR5 no está soportado por la librería junrar (solo RAR4)."
        if (nombre.contains("RarException") && (t.message ?: "").contains("password", true)) return "RAR cifrado: no soportado."
        return t.message ?: nombre
    }
}
