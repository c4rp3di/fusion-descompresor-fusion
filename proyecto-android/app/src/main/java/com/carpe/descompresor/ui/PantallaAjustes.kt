package com.carpe.descompresor.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.carpe.descompresor.apk.GestorKeystore
import com.carpe.descompresor.util.CopiaLocal
import com.carpe.descompresor.util.DetectorDispositivo
import com.carpe.descompresor.util.Formato
import com.carpe.descompresor.util.PreferenciasApp
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class PantallaAjustes(
    private val activity: AppCompatActivity,
    private val vm: ContenedorViewModel,
    private val lanz: Lanzadores
) {
    private val prefs = PreferenciasApp(activity)
    private val scroll = ScrollView(activity)
    private val contenido = Ui.vertical(activity, 16)
    val vista: View get() = scroll

    init {
        scroll.addView(contenido)
        construir()
    }

    // ---------- utilidades de filas ----------
    private fun seccion(titulo: String) {
        contenido.addView(Ui.texto(activity, titulo, 17f, true), Ui.anchoTotal(activity, 20))
        contenido.addView(Ui.separador(activity), Ui.anchoTotal(activity, 4))
    }

    private fun nota(texto: String) {
        val t = Ui.texto(activity, texto, 11f)
        t.alpha = 0.7f
        contenido.addView(t)
    }

    private fun interruptor(titulo: String, clave: String, alCambiar: ((Boolean) -> Unit)? = null) {
        val s = SwitchMaterial(activity)
        s.text = titulo
        s.textSize = 14f * Ui.escala
        s.isChecked = prefs.bool(clave)
        s.setOnCheckedChangeListener { _: CompoundButton, marcado: Boolean ->
            prefs.poner(clave, marcado)
            alCambiar?.invoke(marcado)
        }
        contenido.addView(s, Ui.anchoTotal(activity, 4))
    }

    private fun numero(titulo: String, clave: String, min: Int, max: Int, unidad: String) {
        val fila = Ui.fila(activity)
        val t = Ui.texto(activity, "$titulo ($min–$max $unidad)", 14f)
        t.layoutParams = Ui.pesoUno()
        val campo = EditText(activity)
        campo.inputType = InputType.TYPE_CLASS_NUMBER
        campo.setSingleLine(true)
        campo.setText(prefs.entero(clave).toString())
        campo.minimumWidth = Ui.dp(activity, 90)
        campo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull()
                if (v != null && v in min..max) prefs.poner(clave, v)
            }
        })
        campo.setOnFocusChangeListener { _, tiene ->
            if (!tiene) campo.setText(prefs.entero(clave).toString())
        }
        fila.addView(t)
        fila.addView(campo)
        contenido.addView(fila, Ui.anchoTotal(activity, 4))
    }

    private fun opciones(titulo: String, clave: String, valores: List<Pair<String, String>>, alCambiar: ((String) -> Unit)? = null) {
        contenido.addView(Ui.texto(activity, titulo, 14f), Ui.anchoTotal(activity, 6))
        val sp = Spinner(activity)
        sp.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, valores.map { it.first })
        val actual = prefs.cadena(clave)
        // Selección inicial sin disparar el listener; después se engancha el listener.
        sp.setSelection(valores.indexOfFirst { it.second == actual }.coerceAtLeast(0), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val nuevo = valores[pos].second
                if (nuevo == prefs.cadena(clave)) return   // no cambió nada
                prefs.poner(clave, nuevo)
                alCambiar?.invoke(nuevo)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        contenido.addView(sp)
    }

    private fun pedirTexto(titulo: String, oculto: Boolean, inicial: String, cb: (String) -> Unit) {
        val campo = EditText(activity)
        campo.setText(inicial)
        campo.setSingleLine(true)
        if (oculto) campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val cont = Ui.vertical(activity, 16)
        cont.addView(campo)
        AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setView(cont)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aceptar") { _, _ -> cb(campo.text.toString()) }
            .show()
    }

    // ---------- pantalla ----------
    private fun construir() {
        contenido.removeAllViews()

        seccion("General")
        opciones("Tema", "tema", listOf("Sistema" to "sistema", "Claro" to "claro", "Oscuro" to "oscuro")) { v ->
            val modo = when (v) {
                "claro" -> AppCompatDelegate.MODE_NIGHT_NO
                "oscuro" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            // Se aplica cuando el desplegable ya se ha cerrado (cambiar el tema recrea la pantalla).
            scroll.post {
                if (AppCompatDelegate.getDefaultNightMode() != modo) AppCompatDelegate.setDefaultNightMode(modo)
            }
        }
        contenido.addView(Ui.texto(activity, "Tamaño de fuente de la app", 14f), Ui.anchoTotal(activity, 6))
        val spFuente = Spinner(activity)
        val escalas = listOf(85, 100, 115, 130)
        spFuente.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, escalas.map { "$it %" })
        spFuente.setSelection(escalas.indexOf(prefs.entero("escala_fuente")).coerceAtLeast(1), false)
        spFuente.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (escalas[pos] == prefs.entero("escala_fuente")) return   // no cambió nada
                prefs.poner("escala_fuente", escalas[pos])
                Ui.escala = escalas[pos] / 100f
                spFuente.post { activity.recreate() }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        contenido.addView(spFuente)
        nota("Idioma: español (la interfaz está preparada para traducirse).")

        seccion("Extracción")
        opciones("Destino por defecto", "destino_defecto", listOf("Carpeta elegida (SAF)" to "saf", "Almacenamiento (acceso total)" to "almacenamiento", "Interno de la app" to "interno"))
        interruptor("Conservar estructura de carpetas", "preservar")
        opciones("Si el archivo ya existe", "si_existe", listOf("Renombrar con (1)" to "renombrar", "Sobrescribir" to "sobrescribir", "Omitir" to "omitir"))
        interruptor("Verificar CRC al extraer", "verificar_crc")
        numero("Buffer de copia", "buffer_kb", 16, 1024, "KB")
        nota("La extracción es secuencial (un archivo cada vez) en todos los formatos.")

        seccion("Seguridad y límites")
        numero("Tamaño máx. del contenedor", "max_contenedor_mb", 50, 4096, "MB")
        numero("Tamaño máx. de archivo recibido de otras apps", "max_recibido_mb", 50, 4096, "MB")
        numero("Tamaño máx. por entrada", "max_entrada_mb", 1, 2048, "MB")
        numero("Tamaño total máx. extraído", "max_total_mb", 1, 65536, "MB")
        numero("Ratio máx. descomprimido:comprimido", "ratio_max", 10, 1000, ":1")
        numero("Nº máximo de entradas", "max_entradas", 100, 100000, "")
        nota("Las rutas con «..», absolutas o con letra de unidad se rechazan siempre. No es configurable.")

        seccion("Vista previa")
        interruptor("Vista previa habilitada", "prev_habilitada")
        numero("Texto máx. a previsualizar", "prev_texto_kb", 10, 10240, "KB")
        numero("Imagen máx. a previsualizar", "prev_imagen_mp", 1, 64, "MP")
        interruptor("Permitir scripts en vista previa HTML", "prev_html_scripts")
        opciones("Codificación por defecto si falla la detección", "codificacion_defecto", listOf("UTF-8" to "UTF-8", "Latin-1" to "Latin-1"))

        seccion("APK / Firma")
        interruptor("Firma v1 (jar)", "firma_v1")
        interruptor("Firma v2", "firma_v2")
        interruptor("Firma v3", "firma_v3")
        interruptor("Verificar firma tras firmar", "verificar_tras_firmar")
        interruptor("Verificar alineación tras alinear", "verificar_alineacion")
        nota("v1 solo hace falta si el APK soporta Android 6 o anterior.")
        construirKeystores()

        seccion("Rendimiento / dispositivo")
        contenido.addView(Ui.monoespaciado(activity, DetectorDispositivo.resumen(activity), 11f))
        contenido.addView(Ui.botonSecundario(activity, "Restaurar ajustes recomendados para este dispositivo") {
            AlertDialog.Builder(activity)
                .setTitle("Restaurar")
                .setMessage("Se restablecerán todos los ajustes a los valores recomendados para este dispositivo.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar") { _, _ ->
                    prefs.restablecerTodo(activity)
                    Ui.escala = prefs.entero("escala_fuente") / 100f
                    construir()
                    Ui.toast(activity, "Ajustes restaurados")
                }
                .show()
        }, Ui.anchoTotal(activity, 6))

        seccion("Ayuda")
        contenido.addView(Ui.boton(activity, "Abrir la ayuda") { mostrarAyuda() }, Ui.anchoTotal(activity, 6))

        seccion("Avanzado")
        interruptor("Modo desarrollador (logs detallados)", "modo_dev")
        contenido.addView(Ui.botonSecundario(activity, "Exportar ajustes (JSON)") { exportar() }, Ui.anchoTotal(activity, 6))
        contenido.addView(Ui.botonSecundario(activity, "Importar ajustes (JSON)") { importar() })
        contenido.addView(Ui.botonSecundario(activity, "Limpiar caché y extracciones internas") { limpiar() })
        contenido.addView(
            Ui.monoespaciado(
                activity,
                "Contenedores abiertos: ${prefs.largo("stat_abiertos")}\nArchivos extraídos: ${prefs.largo("stat_extraidas")}\nBytes procesados: ${Formato.bytes(prefs.largo("stat_bytes"))}",
                11f
            ),
            Ui.anchoTotal(activity, 8)
        )
        nota("Descompresor 1.0.0 · com.carpe.descompresor")
    }

    // ---------- keystores ----------
    private fun construirKeystores() {
        contenido.addView(Ui.texto(activity, "Keystores", 14f, true), Ui.anchoTotal(activity, 10))
        try {
            val lista = GestorKeystore.listar(activity)
            val activa = GestorKeystore.activa(activity)
            if (lista.isEmpty()) {
                contenido.addView(Ui.texto(activity, "No hay ninguna keystore importada.", 12f))
            }
            for (k in lista) {
                val fila = Ui.fila(activity)
                val t = Ui.texto(activity, (if (k.id == activa) "● " else "○ ") + k.nombre + " (" + k.alias + ")", 13f)
                t.layoutParams = Ui.pesoUno()
                fila.addView(t)
                if (k.huella.isNotEmpty()) t.text = t.text.toString() + "\nSHA-256: " + k.huella.take(23) + "…"
                fila.addView(Ui.botonSecundario(activity, "Usar") {
                    GestorKeystore.seleccionar(activity, k.id)
                    construir()
                })
                fila.addView(Ui.botonSecundario(activity, "Borrar") {
                    AlertDialog.Builder(activity)
                        .setTitle("Borrar keystore")
                        .setMessage("La keystore es tu llave: si la pierdes no podrás firmar actualizaciones de apps ya instaladas. ¿Borrar «${k.nombre}» de esta app?")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Borrar") { _, _ ->
                            GestorKeystore.eliminar(activity, k.id)
                            construir()
                        }
                        .show()
                })
                contenido.addView(fila)
            }
        } catch (t: Throwable) {
            contenido.addView(Ui.texto(activity, "No se pudo acceder al almacén cifrado: " + Ui.mensajeError(t), 12f))
        }
        contenido.addView(Ui.boton(activity, "Importar keystore (.jks / .keystore / .p12)") { importarKeystore() }, Ui.anchoTotal(activity, 6))
        nota("Se aceptan JKS clásico, PKCS12 y BKS. Las contraseñas se guardan cifradas con Android Keystore.")
    }

    private fun importarKeystore() {
        lanz.abrirDocumento { uri ->
            if (uri == null) return@abrirDocumento
            activity.lifecycleScope.launch {
                try {
                    val nombre = CopiaLocal.nombreDe(activity, uri)
                    val tmp = withContext(Dispatchers.IO) { GestorKeystore.copiarTemporal(activity, uri) }
                    ImportadorKeystore.iniciar(activity, vm, tmp, nombre) { construir() }
                } catch (t: Throwable) {
                    Ui.toast(activity, "No se pudo leer el keystore: " + Ui.mensajeError(t))
                }
            }
        }
    }

    // ---------- avanzado ----------
    private fun exportar() {
        lanz.crearDocumento("ajustes_descompresor.json") { uri ->
            if (uri == null) return@crearDocumento
            try {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(prefs.exportarJson().toByteArray(Charsets.UTF_8)) }
                Ui.toast(activity, "✔ Ajustes exportados")
            } catch (t: Throwable) {
                Ui.toast(activity, "No se pudo exportar: " + Ui.mensajeError(t))
            }
        }
    }

    private fun importar() {
        lanz.abrirDocumento { uri ->
            if (uri == null) return@abrirDocumento
            try {
                val texto = activity.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                    ?: throw IOException("No se pudo leer el archivo")
                val n = prefs.importarJson(texto)
                Ui.escala = prefs.entero("escala_fuente") / 100f
                construir()
                Ui.toast(activity, "✔ $n ajustes importados")
            } catch (t: Throwable) {
                Ui.toast(activity, "No se pudo importar: " + Ui.mensajeError(t))
            }
        }
    }

    private fun limpiar() {
        activity.lifecycleScope.launch {
            val liberado = withContext(Dispatchers.IO) {
                var total = 0L
                val carpetas = listOf(
                    File(activity.cacheDir, "preview"),
                    File(activity.cacheDir, "compartir"),
                    File(activity.cacheDir, "salida_apk"),
                    File(activity.cacheDir, "ks_tmp"),
                    File(activity.cacheDir, "rar_tmp"),
                    File(activity.filesDir, "extracciones")
                )
                for (c in carpetas) {
                    if (c.exists()) {
                        total += c.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        c.deleteRecursively()
                    }
                }
                total
            }
            Ui.toast(activity, "Liberados " + Formato.bytes(liberado))
        }
    }

    // ---------- ayuda ----------
    private val temasAyuda: List<Pair<String, String>> = listOf(
        "Abrir archivos" to "Pulsa «Abrir archivo» y elige uno. Si no hay ninguno abierto verás tus «Recientes»: toca uno para reabrirlo.\n\n" +
            "Tocar una carpeta la despliega; tocar un archivo muestra sus detalles, vista previa y acciones. El cuadro de la izquierda de cada fila marca para extraer (en carpetas marca todo lo que contienen).",
        "Dónde se extrae (destinos)" to "• Carpeta elegida (SAF): eliges una carpeta y la app la recuerda. No necesita permisos especiales y funciona en todos los Android.\n\n" +
            "• Almacenamiento (acceso total): escribe en una ruta de tu almacenamiento. Pide el permiso «Acceso a todos los archivos» en Ajustes del sistema. En Android 10 no está disponible.\n\n" +
            "• Interno de la app: no necesita permisos y permite «Compartir» lo extraído, pero no es visible desde otros gestores de archivos.",
        "Archivos divididos (volúmenes)" to "Pulsa «Abrir archivo» y marca TODAS las partes a la vez; la app las une sola.\n\n" +
            "Se admiten: nombre.zip.001, .002…  ·  nombre.z01, .z02… + nombre.zip  ·  nombre.part1.rar, .part2.rar…  ·  nombre.rar + .r00, .r01…\n\n" +
            "Si falta una parte te dirá cuál. RAR solo en versión 4; con contraseña no se pueden abrir.",
        "Extraer en lote" to "Botón «Lote…»: elige varios archivos o una carpeta con archivos comprimidos. Cada uno se extrae en una subcarpeta con su nombre.\n\n" +
            "Si uno falla, el resto continúa y el informe final lo indica. Los lotes no tienen «Reintentar fallidos», pero sí se pueden reanudar.",
        "Reanudar extracciones" to "Si la app o el sistema se cierran a mitad de una extracción, al volver a abrir la app se te ofrece reanudarla.\n\n" +
            "Se reabre el archivo original, se saltan los ya extraídos y se rehace el que estaba a medias. Para que funcione, el archivo debe seguir accesible (abierto con «Abrir archivo», «Recientes» o «Lote»). No se reanudan los archivos abiertos dentro de otros.",
        "Verificar integridad" to "El botón «Verificar integridad» lee todo el contenido sin escribir nada y comprueba tamaños y CRC. En formatos de flujo (GZ, BZ2, XZ, TAR…) detecta además la corrupción por errores de lectura.\n\n" +
            "Los archivos cifrados no se pueden verificar.",
        "Archivos dentro de archivos" to "En los detalles de un .zip, .tar.gz, .apk, etc. aparece «Abrir como contenedor». El botón «Volver al contenedor anterior» (o el botón Atrás del móvil) regresa al nivel superior.",
        "Abrir desde otras apps" to "Desde cualquier gestor, navegador, WhatsApp, Gmail o Telegram: «Abrir con» o «Compartir con» → Descompresor. El archivo se copia a una caché privada (límite en Seguridad y límites) que se limpia sola.\n\n" +
            "Si tu app no ofrece Descompresor, usa «Abrir archivo» dentro de la app: siempre funciona.",
        "APK: Manifest, edición y firma" to "En la pestaña APK: ver el Manifest, comprobar la firma y la alineación y editar el Manifest (se recompila a binario al guardar).\n\n" +
            "Para cambiar archivos: en Contenedor toca un archivo del APK → reemplazar, editar como texto o eliminar. Luego «Reempaquetar → alinear → firmar». Necesitas una keystore importada: en la pestaña APK, «Importar keystore del ZIP del proyecto…» la busca en el ZIP de GitHub de tu app (proyecto-android/apk-build/debug.keystore) y prueba las credenciales por defecto de Fusion (android / androiddebugkey / android); o impórtala a mano en Ajustes → APK / Firma (JKS, PKCS12 o BKS).\n\n" +
            "La keystore es tu llave: sin ella no podrás firmar actualizaciones de apps ya instaladas.",
        "Editar un ZIP" to "Con un ZIP abierto (o un APK) verás «Añadir archivos…». Toca un archivo o carpeta para renombrarlo, moverlo, reemplazarlo, editarlo como texto o eliminarlo.\n\n" +
            "Los cambios quedan pendientes: pulsa «Cambios (N)…» para revisarlos y «Guardar como…» para crear el ZIP nuevo (el original no se modifica). En un APK los cambios se aplican con «Reempaquetar → alinear → firmar».\n\n" +
            "No se pueden editar ZIP divididos en volúmenes.",
        "Instalar paquetes .apks / .xapk / .apkm" to "Abre el paquete y ve a la pestaña APK: verás cuántos APK contiene y «Instalar paquete». La app elige los splits que corresponden a la arquitectura, densidad de pantalla e idioma de tu dispositivo; si falla, prueba «Todos los splits».\n\n" +
            "Android pedirá confirmar la instalación. Los archivos OBB de los .xapk no se instalan: cópialos tú a Android/obb/<paquete>/.",
        "Cambiar el icono de un APK" to "En la pestaña APK: «Cambiar icono…» → eliges una imagen. La app localiza los archivos reales del icono (leyendo el Manifest y resources.arsc) y los sustituye en todas las densidades; el icono redondo se recorta en círculo.\n\n" +
            "En iconos adaptativos con imágenes se cambia la capa de primer plano. Si el icono es vectorial (XML), se convierte a PNG clásico y pierde el efecto adaptativo.\n\n" +
            "Los cambios quedan pendientes: luego «Reempaquetar → alinear → firmar». Si el APK no tiene icono, la app ofrece añadir uno (función EXPERIMENTAL: crea un recurso nuevo en resources.arsc y pone android:icon en el Manifest). Si el resultado no se instala, descarta los cambios pendientes. Con otra firma, Android no lo instala como actualización de la app original.",
        "Límites y seguridad" to "Se rechazan siempre las rutas con «..», absolutas o con letra de unidad. Los límites de tamaño, ratio de compresión y nº de entradas protegen de «bombas» de descompresión y se pueden ajustar en Seguridad y límites.\n\n" +
            "Una entrada que supera un límite se omite y aparece en el informe con el motivo.",
        "Formatos y contraseñas no soportados" to "No se pueden abrir: ZIP, 7z y RAR con contraseña, RAR5, ni 7z con métodos BCJ2 o PPMd. Tampoco se crea RAR ni 7z.\n\n" +
            "Sí: ZIP, TAR, TAR.GZ/BZ2/XZ, GZ, BZ2, XZ, 7z, RAR4, CPIO, AR, DEB y APK. Se puede crear ZIP, TAR y TAR.GZ.",
        "Si algo falla" to "• «No aparece en Abrir con»: usa «Abrir archivo» en la app.\n" +
            "• Extracción SAF vacía o con errores: elige otra carpeta (algunos proveedores como Drive fallan al escribir).\n" +
            "• Un archivo se omite: mira el motivo en el informe (límite, ruta insegura, cifrado…).\n" +
            "• Falta espacio: «Limpiar caché y extracciones internas» en Avanzado.\n" +
            "• Quieres empezar de cero: «Restaurar ajustes recomendados»."
    )

    private fun mostrarAyuda() {
        val titulos = temasAyuda.map { it.first }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Ayuda")
            .setItems(titulos) { _, i ->
                val tv = Ui.texto(activity, temasAyuda[i].second, 14f)
                val p = Ui.dp(activity, 16)
                tv.setPadding(p, p, p, p)
                val sv = ScrollView(activity)
                sv.addView(tv)
                AlertDialog.Builder(activity)
                    .setTitle(temasAyuda[i].first)
                    .setView(sv)
                    .setPositiveButton("Cerrar", null)
                    .setNegativeButton("Más temas") { _, _ -> mostrarAyuda() }
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }
}
