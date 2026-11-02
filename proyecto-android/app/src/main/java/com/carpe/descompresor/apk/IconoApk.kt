package com.carpe.descompresor.apk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class ArchivoIcono(val ruta: String, val densidad: Int)
class XmlConvertible(val ruta: String, val indiceCadena: Int, val redondo: Boolean)

class PlanIcono(
    val clasicos: List<ArchivoIcono>,
    val redondos: List<ArchivoIcono>,
    val primerPlano: List<ArchivoIcono>,
    val aConvertir: List<XmlConvertible>,
    val avisos: List<String>,
    val sinIcono: Boolean
) {
    fun acciones(): Int = clasicos.size + redondos.size + primerPlano.size + aConvertir.size

    fun resumen(): String {
        val sb = StringBuilder()
        if (clasicos.isNotEmpty()) sb.append("• Icono clásico: ${clasicos.size} imagen(es)\n")
        if (redondos.isNotEmpty()) sb.append("• Icono redondo: ${redondos.size} imagen(es) (se recorta en círculo)\n")
        if (primerPlano.isNotEmpty()) sb.append("• Icono adaptativo: ${primerPlano.size} imagen(es) de la capa de primer plano (el fondo se mantiene)\n")
        if (aConvertir.isNotEmpty()) sb.append("• ${aConvertir.size} icono(s) en XML/vectorial: se convertirán a PNG clásico (pierden el efecto adaptativo)\n")
        for (a in avisos) sb.append("⚠ ").append(a).append('\n')
        return sb.toString()
    }
}

/** Localiza el icono de un APK (Manifest + resources.arsc) y lo sustituye por otra imagen. */
object IconoApk {
    private val raster = setOf("png", "webp", "jpg", "jpeg")

    private fun idDe(valor: String?): Int? {
        if (valor == null || !valor.startsWith("@0x")) return null
        return valor.substring(3).toLongOrNull(16)?.toInt()
    }

    fun planificar(apk: File): PlanIcono {
        val clasicos = ArrayList<ArchivoIcono>()
        val redondos = ArrayList<ArchivoIcono>()
        val primerPlano = ArrayList<ArchivoIcono>()
        val aConvertir = ArrayList<XmlConvertible>()
        val avisos = ArrayList<String>()
        val vistos = HashSet<String>()

        ZipFile(apk).use { z ->
            val man = z.getEntry("AndroidManifest.xml") ?: throw IOException("El APK no contiene AndroidManifest.xml")
            val raiz = ParserAxml.parsear(z.getInputStream(man).use { it.readBytes() }).raiz
                ?: throw IOException("Manifest vacío")
            val app = raiz.hijos.firstOrNull { it.tag == "application" }
            val idIcono = idDe(app?.atributos?.get("android:icon"))
            val idRedondo = idDe(app?.atributos?.get("android:roundIcon"))
            if (idIcono == null && idRedondo == null) {
                return PlanIcono(clasicos, redondos, primerPlano, aConvertir, avisos, true)
            }
            val ea = z.getEntry("resources.arsc")
            if (ea == null) {
                avisos.add("El APK no tiene resources.arsc")
                return PlanIcono(clasicos, redondos, primerPlano, aConvertir, avisos, true)
            }
            val tabla = TablaArsc.leer(z.getInputStream(ea).use { it.readBytes() })

            fun procesar(id: Int, redondo: Boolean) {
                val valores = tabla.resolver(id)
                if (valores.isEmpty()) {
                    avisos.add("No se encontró el recurso del icono (0x" + Integer.toHexString(id) + ")")
                    return
                }
                for (v in valores) {
                    if (v.tipo != 0x03) {
                        avisos.add("El icono no es un archivo en una de sus configuraciones (tipo de dato ${v.tipo})")
                        continue
                    }
                    val ruta = tabla.cadenas.getOrNull(v.dato) ?: continue
                    val ext = ruta.substringAfterLast('.', "").lowercase()
                    if (ext in raster) {
                        if (vistos.add(ruta)) (if (redondo) redondos else clasicos).add(ArchivoIcono(ruta, v.densidad))
                    } else if (ext == "xml") {
                        val e = z.getEntry(ruta)
                        if (e == null) {
                            avisos.add("Falta $ruta en el APK")
                            continue
                        }
                        val bytes = z.getInputStream(e).use { it.readBytes() }
                        if (!ParserAxml.esAxml(bytes)) {
                            avisos.add("$ruta no es XML binario")
                            continue
                        }
                        val nodo = try { ParserAxml.parsear(bytes).raiz } catch (x: Exception) { null }
                        var rasterEnPrimerPlano = false
                        if (nodo != null && nodo.tag == "adaptive-icon") {
                            val fg = nodo.hijos.firstOrNull { it.tag == "foreground" }
                            val idFg = idDe(fg?.atributos?.get("android:drawable"))
                            if (idFg != null) {
                                for (vf in tabla.resolver(idFg)) {
                                    if (vf.tipo != 0x03) continue
                                    val rf = tabla.cadenas.getOrNull(vf.dato) ?: continue
                                    if (rf.substringAfterLast('.', "").lowercase() in raster) {
                                        rasterEnPrimerPlano = true
                                        if (vistos.add(rf)) primerPlano.add(ArchivoIcono(rf, vf.densidad))
                                    }
                                }
                            }
                        }
                        if (!rasterEnPrimerPlano && vistos.add(ruta)) {
                            aConvertir.add(XmlConvertible(ruta, v.dato, redondo))
                        }
                    } else {
                        avisos.add("Tipo de archivo de icono no soportado: $ruta")
                    }
                }
            }

            if (idIcono != null) procesar(idIcono, false)
            if (idRedondo != null && idRedondo != idIcono) procesar(idRedondo, true)
        }
        return PlanIcono(clasicos, redondos, primerPlano, aConvertir, avisos, false)
    }

    // ---------- imagen ----------
    fun decodificar(ctx: Context, uri: Uri): Bitmap {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("No se pudo abrir la imagen")
        if (bytes.size > 40 * 1024 * 1024) throw IOException("La imagen es demasiado grande")
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) throw IOException("El archivo no es una imagen válida")
        var muestra = 1
        while (maxOf(o.outWidth, o.outHeight) / muestra > 1600) muestra *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = muestra
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) ?: throw IOException("No se pudo decodificar la imagen")
    }

    private fun tamClasico(densidad: Int): Int =
        if (densidad in 1..1000) (densidad * 48 / 160).coerceAtLeast(36) else 192

    private fun tamCapa(densidad: Int): Int =
        if (densidad in 1..1000) (densidad * 108 / 160).coerceAtLeast(108) else 432

    private fun cuadrado(src: Bitmap, lado: Int): Bitmap {
        val salida = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val c = Canvas(salida)
        val escala = maxOf(lado.toFloat() / src.width, lado.toFloat() / src.height)
        val m = Matrix()
        m.setScale(escala, escala)
        m.postTranslate((lado - src.width * escala) / 2f, (lado - src.height * escala) / 2f)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return salida
    }

    private fun renderClasico(src: Bitmap, lado: Int, redondo: Boolean): Bitmap {
        val q = cuadrado(src, lado)
        if (!redondo) return q
        val salida = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = BitmapShader(q, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        Canvas(salida).drawCircle(lado / 2f, lado / 2f, lado / 2f, p)
        return salida
    }

    private fun renderCapa(src: Bitmap, lado: Int, rellenar: Boolean): Bitmap {
        if (rellenar) return cuadrado(src, lado)
        val interior = lado * 72 / 108
        val q = cuadrado(src, interior)
        val salida = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        Canvas(salida).drawBitmap(q, ((lado - interior) / 2).toFloat(), ((lado - interior) / 2).toFloat(), null)
        return salida
    }

    private fun png(b: Bitmap): ByteArray {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, o)
        return o.toByteArray()
    }

    /**
     * Registra en [cambios] los reemplazos necesarios. Después hay que reempaquetar, alinear y firmar.
     * [rellenar]: la capa de primer plano del icono adaptativo ocupa todo el lienzo (si no, deja margen).
     */
    fun aplicar(ctx: Context, apk: File, plan: PlanIcono, src: Bitmap, rellenar: Boolean, cambios: CambiosApk): String {
        val dir = File(ctx.cacheDir, "cambios_apk")
        dir.mkdirs()
        var contador = 0

        fun poner(ruta: String, bytes: ByteArray) {
            val f = File(dir, "i_" + System.currentTimeMillis() + "_" + (contador++))
            f.writeBytes(bytes)
            cambios.reemplazos[ruta]?.delete()
            cambios.reemplazos[ruta] = f
            cambios.eliminados.remove(ruta)
            cambios.renombres.remove(ruta)
        }

        for (a in plan.clasicos) poner(a.ruta, png(renderClasico(src, tamClasico(a.densidad), false)))
        for (a in plan.redondos) poner(a.ruta, png(renderClasico(src, tamClasico(a.densidad), true)))
        for (a in plan.primerPlano) poner(a.ruta, png(renderCapa(src, tamCapa(a.densidad), rellenar)))

        if (plan.aConvertir.isNotEmpty()) {
            val existentes = HashSet<String>()
            var arsc = ByteArray(0)
            ZipFile(apk).use { z ->
                val en = z.entries()
                while (en.hasMoreElements()) existentes.add(en.nextElement().name)
                val previo = cambios.reemplazos["resources.arsc"]
                arsc = if (previo != null) previo.readBytes() else {
                    val e = z.getEntry("resources.arsc") ?: throw IOException("El APK no tiene resources.arsc")
                    z.getInputStream(e).use { it.readBytes() }
                }
            }
            val tabla = TablaArsc.leer(arsc)
            for (x in plan.aConvertir) {
                var parcheado: ByteArray? = null
                var nuevaRuta = ""
                var n = 0
                while (parcheado == null && n < 2000) {
                    val candidato = "res/" + nombreCorto(n) + ".png"
                    n++
                    if (candidato in existentes || cambios.reemplazos.containsKey(candidato)) continue
                    val p = tabla.parchear(arsc, x.indiceCadena, candidato)
                    if (p != null) {
                        parcheado = p
                        nuevaRuta = candidato
                    }
                }
                if (parcheado == null) {
                    throw IOException("No se pudo convertir ${x.ruta}: su ruta es demasiado corta para sustituirla")
                }
                arsc = parcheado
                existentes.add(nuevaRuta)
                poner(nuevaRuta, png(renderClasico(src, 192, x.redondo)))
            }
            poner("resources.arsc", arsc)
        }
        return "Icono preparado: ${plan.acciones()} cambio(s) pendientes. Ahora pulsa «Reempaquetar → alinear → firmar»."
    }

    private fun nombreCorto(n: Int): String {
        val letras = "abcdefghijklmnopqrstuvwxyz"
        var i = n
        val sb = StringBuilder()
        do {
            sb.append(letras[i % 26])
            i = i / 26 - 1
        } while (i >= 0)
        return sb.reverse().toString()
    }
}
