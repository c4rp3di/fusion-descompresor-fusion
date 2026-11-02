package com.carpe.descompresor.apk

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayOutputStream
import java.io.StringReader

/**
 * Compila XML de texto a XML binario de Android (AXML).
 * Los IDs de recurso de los atributos se toman del archivo original (así se garantiza que son correctos)
 * y, para atributos nuevos, de una tabla interna con los más comunes del Manifest.
 */
object EscritorAxml {
    class ErrorAxml(mensaje: String) : Exception(mensaje)

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private const val T_REFERENCIA = 0x01
    private const val T_ATRIBUTO = 0x02
    private const val T_CADENA = 0x03
    private const val T_FLOTANTE = 0x04
    private const val T_ENTERO = 0x10
    private const val T_HEX = 0x11
    private const val T_BOOL = 0x12
    private const val T_COLOR8 = 0x1C

    /** IDs de atributos android: que conozco con seguridad (R.attr.*). */
    private val IDS_ANDROID: Map<String, Int> = mapOf(
        "theme" to 0x01010000, "label" to 0x01010001, "icon" to 0x01010002, "name" to 0x01010003,
        "manageSpaceActivity" to 0x01010004, "allowClearUserData" to 0x01010005, "permission" to 0x01010006,
        "readPermission" to 0x01010007, "writePermission" to 0x01010008, "protectionLevel" to 0x01010009,
        "permissionGroup" to 0x0101000a, "sharedUserId" to 0x0101000b, "hasCode" to 0x0101000c,
        "persistent" to 0x0101000d, "enabled" to 0x0101000e, "debuggable" to 0x0101000f,
        "exported" to 0x01010010, "process" to 0x01010011, "taskAffinity" to 0x01010012,
        "multiprocess" to 0x01010013, "finishOnTaskLaunch" to 0x01010014, "clearTaskOnLaunch" to 0x01010015,
        "stateNotNeeded" to 0x01010016, "excludeFromRecents" to 0x01010017, "authorities" to 0x01010018,
        "syncable" to 0x01010019, "initOrder" to 0x0101001a, "grantUriPermissions" to 0x0101001b,
        "priority" to 0x0101001c, "launchMode" to 0x0101001d, "screenOrientation" to 0x0101001e,
        "configChanges" to 0x0101001f, "description" to 0x01010020, "targetPackage" to 0x01010021,
        "handleProfiling" to 0x01010022, "functionalTest" to 0x01010023, "value" to 0x01010024,
        "resource" to 0x01010025, "mimeType" to 0x01010026, "scheme" to 0x01010027, "host" to 0x01010028,
        "port" to 0x01010029, "path" to 0x0101002a, "pathPrefix" to 0x0101002b, "pathPattern" to 0x0101002c,
        "minSdkVersion" to 0x0101020c, "versionCode" to 0x0101021b, "versionName" to 0x0101021c,
        "windowSoftInputMode" to 0x0101022b, "targetSdkVersion" to 0x01010270, "maxSdkVersion" to 0x01010271,
        "allowBackup" to 0x01010280, "glEsVersion" to 0x01010281, "required" to 0x0101028e,
        "installLocation" to 0x010102b7, "hardwareAccelerated" to 0x010102d3, "largeHeap" to 0x0101035a,
        "supportsRtl" to 0x010103af, "extractNativeLibs" to 0x010104ea, "usesCleartextTraffic" to 0x010104ec,
        "networkSecurityConfig" to 0x01010527, "roundIcon" to 0x0101052c, "foregroundServiceType" to 0x01010599,
        "requestLegacyExternalStorage" to 0x01010603
    )

    private val BOOLEANOS = setOf(
        "exported", "debuggable", "allowBackup", "enabled", "required", "hasCode", "persistent", "largeHeap",
        "supportsRtl", "usesCleartextTraffic", "requestLegacyExternalStorage", "hardwareAccelerated",
        "extractNativeLibs", "grantUriPermissions", "multiprocess", "finishOnTaskLaunch", "clearTaskOnLaunch",
        "stateNotNeeded", "excludeFromRecents", "syncable", "allowClearUserData", "handleProfiling", "functionalTest"
    )

    private val ENTEROS = setOf("versionCode", "minSdkVersion", "targetSdkVersion", "maxSdkVersion", "priority", "initOrder", "port")

    private class Simbolico(val banderas: Boolean, val valores: Map<String, Int>)

    private val SIMBOLICOS: Map<String, Simbolico> = mapOf(
        "launchMode" to Simbolico(false, mapOf("standard" to 0, "singleTop" to 1, "singleTask" to 2, "singleInstance" to 3, "singleInstancePerTask" to 4)),
        "screenOrientation" to Simbolico(false, mapOf(
            "unspecified" to -1, "landscape" to 0, "portrait" to 1, "user" to 2, "behind" to 3, "sensor" to 4,
            "nosensor" to 5, "sensorLandscape" to 6, "sensorPortrait" to 7, "reverseLandscape" to 8,
            "reversePortrait" to 9, "fullSensor" to 10, "userLandscape" to 11, "userPortrait" to 12,
            "fullUser" to 13, "locked" to 14
        )),
        "installLocation" to Simbolico(false, mapOf("auto" to 0, "internalOnly" to 1, "preferExternal" to 2)),
        "configChanges" to Simbolico(true, mapOf(
            "mcc" to 0x1, "mnc" to 0x2, "locale" to 0x4, "touchscreen" to 0x8, "keyboard" to 0x10,
            "keyboardHidden" to 0x20, "navigation" to 0x40, "orientation" to 0x80, "screenLayout" to 0x100,
            "uiMode" to 0x200, "screenSize" to 0x400, "smallestScreenSize" to 0x800, "density" to 0x1000,
            "layoutDirection" to 0x2000, "colorMode" to 0x4000, "fontScale" to 0x40000000
        )),
        "windowSoftInputMode" to Simbolico(true, mapOf(
            "stateUnspecified" to 0, "stateUnchanged" to 1, "stateHidden" to 2, "stateAlwaysHidden" to 3,
            "stateVisible" to 4, "stateAlwaysVisible" to 5, "adjustUnspecified" to 0, "adjustResize" to 0x10,
            "adjustPan" to 0x20, "adjustNothing" to 0x30
        )),
        "protectionLevel" to Simbolico(true, mapOf(
            "normal" to 0, "dangerous" to 1, "signature" to 2, "signatureOrSystem" to 3, "privileged" to 0x10,
            "system" to 0x10, "development" to 0x20, "appop" to 0x40, "pre23" to 0x80, "installer" to 0x100,
            "verifier" to 0x200, "preinstalled" to 0x400
        )),
        "foregroundServiceType" to Simbolico(true, mapOf(
            "dataSync" to 0x1, "mediaPlayback" to 0x2, "phoneCall" to 0x4, "location" to 0x8,
            "connectedDevice" to 0x10, "mediaProjection" to 0x20, "camera" to 0x40, "microphone" to 0x80,
            "health" to 0x100, "remoteMessaging" to 0x200, "systemExempted" to 0x400, "shortService" to 0x800,
            "fileManagement" to 0x1000, "specialUse" to 0x40000000
        ))
    )

    private class Attr(val uri: String, val nombre: String, val valor: String)

    private class Ev(
        val tipo: Int,            // 0 inicio ns, 1 fin ns, 2 inicio elemento, 3 fin elemento
        val prefijo: String,
        val uri: String,
        val nombre: String,
        val attrs: List<Attr>,
        val linea: Int
    )

    private class Valor(val raw: Int, val tipo: Int, val dato: Int)

    private class Tipado(val ids: Map<String, Int>, val tipos: Map<String, Int>, val exactos: Map<String, Pair<Int, Int>>)

    private fun leerOriginal(original: ByteArray): Tipado {
        val ids = HashMap<String, Int>()
        val tipos = HashMap<String, Int>()
        val exactos = HashMap<String, Pair<Int, Int>>()
        ParserAxml.parsear(original, ObservadorAtributo { uri, nombre, id, texto, tipo, dato ->
            val k = "$uri|$nombre"
            if (id != 0) ids[k] = id
            tipos[k] = tipo
            if (tipo != T_CADENA) exactos["$k|$texto"] = Pair(tipo, dato)
        })
        return Tipado(ids, tipos, exactos)
    }

    private fun parsearXml(xml: String): List<Ev> {
        val p = Xml.newPullParser()
        try {
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(StringReader(xml))
            val eventos = ArrayList<Ev>()
            val declaraciones = ArrayList<List<Pair<String, String>>>()
            var raices = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val d = p.depth
                        if (d == 1) raices++
                        val desde = p.getNamespaceCount(d - 1)
                        val hasta = p.getNamespaceCount(d)
                        val decl = ArrayList<Pair<String, String>>()
                        for (i in desde until hasta) {
                            decl.add(Pair(p.getNamespacePrefix(i) ?: "", p.getNamespaceUri(i) ?: ""))
                        }
                        for (pr in decl) eventos.add(Ev(0, pr.first, pr.second, "", emptyList(), p.lineNumber))
                        val attrs = ArrayList<Attr>()
                        for (i in 0 until p.attributeCount) {
                            attrs.add(Attr(p.getAttributeNamespace(i) ?: "", p.getAttributeName(i), p.getAttributeValue(i)))
                        }
                        eventos.add(Ev(2, "", p.namespace ?: "", p.name, attrs, p.lineNumber))
                        declaraciones.add(decl)
                    }
                    XmlPullParser.END_TAG -> {
                        eventos.add(Ev(3, "", p.namespace ?: "", p.name, emptyList(), p.lineNumber))
                        val decl = declaraciones.removeAt(declaraciones.size - 1)
                        for (i in decl.indices.reversed()) {
                            eventos.add(Ev(1, decl[i].first, decl[i].second, "", emptyList(), p.lineNumber))
                        }
                    }
                    else -> { }
                }
                ev = p.next()
            }
            if (raices != 1) throw ErrorAxml("El XML debe tener exactamente un elemento raíz")
            return eventos
        } catch (e: XmlPullParserException) {
            throw ErrorAxml("XML mal formado (línea ${p.lineNumber}): ${e.message}")
        }
    }

    private fun idDe(uri: String, nombre: String, t: Tipado): Int {
        val k = "$uri|$nombre"
        val ori = t.ids[k]
        if (ori != null) return ori
        if (uri == ANDROID_NS) {
            return IDS_ANDROID[nombre] ?: throw ErrorAxml(
                "Atributo «android:$nombre» desconocido: no puedo asignarle su ID de recurso. " +
                    "Solo se admiten atributos que ya existían en el archivo y los más comunes del Manifest."
            )
        }
        if (uri.isNotEmpty()) {
            throw ErrorAxml("Atributo «$nombre» del espacio de nombres $uri sin ID conocido")
        }
        return 0
    }

    private fun entero(s: String): Int? {
        val t = s.trim()
        if (t.startsWith("0x") || t.startsWith("0X")) return t.substring(2).toLongOrNull(16)?.toInt()
        return t.toLongOrNull()?.toInt()
    }

    private fun valorTipado(uri: String, nombre: String, texto: String, t: Tipado, pool: Pool): Valor {
        val k = "$uri|$nombre"
        val exacto = t.exactos["$k|$texto"]
        if (exacto != null) return Valor(-1, exacto.first, exacto.second)
        val orig = t.tipos[k]
        val esAndroid = uri == ANDROID_NS

        if (esAndroid) {
            val sim = SIMBOLICOS[nombre]
            val numericoOrig = orig == null || orig == T_ENTERO || orig == T_HEX
            if (sim != null && numericoOrig && texto.isNotBlank() && entero(texto) == null) {
                var acumulado = 0
                val partes = if (sim.banderas) texto.split('|') else listOf(texto)
                for (parte in partes) {
                    val tk = parte.trim()
                    val v = sim.valores[tk] ?: entero(tk)
                        ?: throw ErrorAxml("Valor «$tk» no reconocido para android:$nombre")
                    acumulado = acumulado or v
                }
                return Valor(-1, if (sim.banderas) T_HEX else T_ENTERO, acumulado)
            }
        }
        if (texto.startsWith("@0x")) {
            val v = texto.substring(3).toLongOrNull(16)
            if (v != null) return Valor(-1, T_REFERENCIA, v.toInt())
        }
        if (texto.startsWith("?0x")) {
            val v = texto.substring(3).toLongOrNull(16)
            if (v != null) return Valor(-1, T_ATRIBUTO, v.toInt())
        }
        if (texto.startsWith("0x") && texto.length in 3..10) {
            val v = texto.substring(2).toLongOrNull(16)
            if (v != null && (orig == null || orig == T_HEX || orig == T_ENTERO)) return Valor(-1, T_HEX, v.toInt())
        }
        if ((texto == "true" || texto == "false") && (orig == T_BOOL || (orig == null && nombre in BOOLEANOS))) {
            return Valor(-1, T_BOOL, if (texto == "true") -1 else 0)
        }
        val n = texto.trim().toLongOrNull()
        if (n != null && (orig == T_ENTERO || (orig == null && nombre in ENTEROS))) {
            return Valor(-1, T_ENTERO, n.toInt())
        }
        if (texto.startsWith("#") && orig != null && orig in 0x1C..0x1F) {
            val h = texto.substring(1).toLongOrNull(16)
            if (h != null) return Valor(-1, T_COLOR8, h.toInt())
        }
        if (orig == T_FLOTANTE) {
            val f = texto.toFloatOrNull()
            if (f != null) return Valor(-1, T_FLOTANTE, java.lang.Float.floatToIntBits(f))
        }
        val idx = pool.indice(texto)
        return Valor(idx, T_CADENA, idx)
    }

    private class Pool {
        val cadenas = ArrayList<String>()
        private val mapa = HashMap<String, Int>()
        fun indice(s: String): Int {
            val e = mapa[s]
            if (e != null) return e
            val i = cadenas.size
            cadenas.add(s)
            mapa[s] = i
            return i
        }
    }

    private class Buf {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) { o.write(v and 0xFF) }
        fun u16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }
        fun bytes(b: ByteArray) { o.write(b) }
        fun tam(): Int = o.size()
        fun toByteArray(): ByteArray = o.toByteArray()
    }

    fun compilar(xml: String, original: ByteArray): ByteArray {
        val tipado = leerOriginal(original)
        val eventos = parsearXml(xml)
        val pool = Pool()

        // 1) Nombres de atributos con ID primero: sus índices deben coincidir con el mapa de recursos.
        val idsOrden = ArrayList<Int>()
        val idPorNombre = HashMap<String, Int>()
        for (e in eventos) {
            if (e.tipo != 2) continue
            for (a in e.attrs) {
                val id = idDe(a.uri, a.nombre, tipado)
                if (id != 0) {
                    val previo = idPorNombre[a.nombre]
                    if (previo != null && previo != id) {
                        throw ErrorAxml("El atributo «${a.nombre}» aparece con dos IDs distintos; no se puede compilar")
                    }
                    if (previo == null) {
                        idPorNombre[a.nombre] = id
                        pool.indice(a.nombre)
                        idsOrden.add(id)
                    }
                }
            }
        }
        if (pool.cadenas.size != idsOrden.size) throw ErrorAxml("Error interno al ordenar atributos")

        // 2) Cuerpo (elementos), registrando el resto de cadenas.
        val cuerpo = Buf()
        for (e in eventos) {
            when (e.tipo) {
                0, 1 -> {
                    val pref = if (e.prefijo.isEmpty()) -1 else pool.indice(e.prefijo)
                    val uri = pool.indice(e.uri)
                    cuerpo.u16(if (e.tipo == 0) 0x0100 else 0x0101)
                    cuerpo.u16(16)
                    cuerpo.u32(24)
                    cuerpo.u32(e.linea)
                    cuerpo.u32(-1)
                    cuerpo.u32(pref)
                    cuerpo.u32(uri)
                }
                2 -> {
                    val ns = if (e.uri.isEmpty()) -1 else pool.indice(e.uri)
                    val nombre = pool.indice(e.nombre)
                    class AttrC(val ns: Int, val nombre: Int, val id: Int, val v: Valor)
                    val lista = ArrayList<AttrC>()
                    for (a in e.attrs) {
                        val id = idDe(a.uri, a.nombre, tipado)
                        val ans = if (a.uri.isEmpty()) -1 else pool.indice(a.uri)
                        val an = pool.indice(a.nombre)
                        lista.add(AttrC(ans, an, id, valorTipado(a.uri, a.nombre, a.valor, tipado, pool)))
                    }
                    // aapt ordena los atributos por ID de recurso; los que no tienen ID van al final
                    val ordenados = lista.withIndex().sortedWith(
                        compareBy<IndexedValue<AttrC>>({ if (it.value.id != 0) 0 else 1 }, { if (it.value.id != 0) it.value.id else 0 }, { it.index })
                    ).map { it.value }
                    cuerpo.u16(0x0102)
                    cuerpo.u16(16)
                    cuerpo.u32(36 + 20 * ordenados.size)
                    cuerpo.u32(e.linea)
                    cuerpo.u32(-1)
                    cuerpo.u32(ns)
                    cuerpo.u32(nombre)
                    cuerpo.u16(20)
                    cuerpo.u16(20)
                    cuerpo.u16(ordenados.size)
                    cuerpo.u16(0)
                    cuerpo.u16(0)
                    cuerpo.u16(0)
                    for (a in ordenados) {
                        cuerpo.u32(a.ns)
                        cuerpo.u32(a.nombre)
                        cuerpo.u32(a.v.raw)
                        cuerpo.u16(8)
                        cuerpo.u8(0)
                        cuerpo.u8(a.v.tipo)
                        cuerpo.u32(a.v.dato)
                    }
                }
                3 -> {
                    val ns = if (e.uri.isEmpty()) -1 else pool.indice(e.uri)
                    val nombre = pool.indice(e.nombre)
                    cuerpo.u16(0x0103)
                    cuerpo.u16(16)
                    cuerpo.u32(24)
                    cuerpo.u32(e.linea)
                    cuerpo.u32(-1)
                    cuerpo.u32(ns)
                    cuerpo.u32(nombre)
                }
            }
        }

        // 3) Tabla de cadenas (UTF-16)
        val n = pool.cadenas.size
        val datos = Buf()
        val offsets = IntArray(n)
        for (i in 0 until n) {
            offsets[i] = datos.tam()
            val s = pool.cadenas[i]
            if (s.length > 0x7FFF) {
                datos.u16(((s.length shr 16) and 0x7FFF) or 0x8000)
                datos.u16(s.length and 0xFFFF)
            } else {
                datos.u16(s.length)
            }
            for (c in s) datos.u16(c.code)
            datos.u16(0)
        }
        while (datos.tam() % 4 != 0) datos.u8(0)
        val inicioCadenas = 28 + 4 * n
        val pb = Buf()
        pb.u16(0x0001)
        pb.u16(28)
        pb.u32(inicioCadenas + datos.tam())
        pb.u32(n)
        pb.u32(0)
        pb.u32(0)
        pb.u32(inicioCadenas)
        pb.u32(0)
        for (o in offsets) pb.u32(o)
        pb.bytes(datos.toByteArray())

        // 4) Mapa de recursos
        val rb = Buf()
        if (idsOrden.isNotEmpty()) {
            rb.u16(0x0180)
            rb.u16(8)
            rb.u32(8 + 4 * idsOrden.size)
            for (id in idsOrden) rb.u32(id)
        }

        val total = 8 + pb.tam() + rb.tam() + cuerpo.tam()
        val fin = Buf()
        fin.u16(0x0003)
        fin.u16(8)
        fin.u32(total)
        fin.bytes(pb.toByteArray())
        fin.bytes(rb.toByteArray())
        fin.bytes(cuerpo.toByteArray())
        val resultado = fin.toByteArray()

        // Validación: el resultado debe poder leerse de nuevo
        try {
            val r = ParserAxml.parsear(resultado)
            if (r.raiz == null) throw ErrorAxml("El resultado no tiene elemento raíz")
        } catch (e: ErrorAxml) {
            throw e
        } catch (e: Exception) {
            throw ErrorAxml("El XML binario generado no es válido: ${e.message}")
        }
        return resultado
    }
}
