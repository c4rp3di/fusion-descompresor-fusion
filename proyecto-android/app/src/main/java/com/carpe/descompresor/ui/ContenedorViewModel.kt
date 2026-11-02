package com.carpe.descompresor.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carpe.descompresor.apk.CambiosApk
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.motor.CatalogoMotores
import com.carpe.descompresor.util.ContadorInput
import com.carpe.descompresor.util.CopiaCancelada
import com.carpe.descompresor.util.CopiaLocal
import com.carpe.descompresor.util.Historial
import com.carpe.descompresor.util.Iconos
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class Nodo(val ruta: String, val nombre: String, val esDir: Boolean, val entrada: Entrada?, val nivel: Int) {
    val hijos = ArrayList<Nodo>()
    var tamano: Long = 0L
    var archivos: List<Nodo> = emptyList()
}

object ArbolBuilder {
    fun construir(entradas: List<Entrada>): Nodo {
        val raiz = Nodo("", "", true, null, -1)
        val dirs = HashMap<String, Nodo>()
        dirs[""] = raiz

        fun dir(ruta: String): Nodo {
            val existente = dirs[ruta]
            if (existente != null) return existente
            val i = ruta.lastIndexOf('/')
            val padre = dir(if (i < 0) "" else ruta.substring(0, i))
            val n = Nodo(ruta, ruta.substring(i + 1), true, null, padre.nivel + 1)
            padre.hijos.add(n)
            dirs[ruta] = n
            return n
        }

        for (e in entradas) {
            val r = e.ruta.replace('\\', '/').trim('/')
            if (r.isEmpty()) continue
            if (e.esDirectorio) {
                dir(r)
                continue
            }
            val i = r.lastIndexOf('/')
            val padre = dir(if (i < 0) "" else r.substring(0, i))
            padre.hijos.add(Nodo(r, r.substring(i + 1), false, e, padre.nivel + 1))
        }
        cerrar(raiz)
        return raiz
    }

    private fun cerrar(n: Nodo) {
        n.hijos.sortWith(compareBy<Nodo>({ !it.esDir }, { it.nombre.lowercase() }))
        if (!n.esDir) {
            n.tamano = n.entrada?.tamano?.coerceAtLeast(0L) ?: 0L
            return
        }
        val hojas = ArrayList<Nodo>()
        var t = 0L
        for (h in n.hijos) {
            cerrar(h)
            t += h.tamano
            if (h.esDir) hojas.addAll(h.archivos) else hojas.add(h)
        }
        n.tamano = t
        n.archivos = hojas
    }
}

data class ProgresoApertura(
    val activo: Boolean = false,
    val hecho: Long = 0L,
    val total: Long = -1L,
    val texto: String = ""
)

class ContenedorViewModel : ViewModel() {
    var contenedor: Contenedor? = null
        private set
    var raiz: Nodo? = null
        private set
    var expandidos: HashSet<String> = HashSet()
        private set
    var seleccion: HashSet<String> = HashSet()
        private set
    var consulta: String = ""
    var filtro: Iconos.Categoria? = null
    var cambiosApk: CambiosApk = CambiosApk()
        private set
    var cargando: Boolean = false
        private set
    var error: String? = null

    /** Pestaña activa (id del menú); 0 = Contenedor. Sobrevive a recreaciones (cambio de tema, giro). */
    var pestana: Int = 0

    val revision = MutableStateFlow(0)
    val progreso = MutableStateFlow(ProgresoApertura())

    @Volatile
    private var cancelarFlag: Boolean = false

    /** Contenedores "padre" cuando se abre un archivo dentro de otro. */
    private class Guardado(
        val c: Contenedor,
        val raiz: Nodo?,
        val expandidos: HashSet<String>,
        val seleccion: HashSet<String>,
        val consulta: String,
        val filtro: Iconos.Categoria?,
        val cambios: CambiosApk
    )

    private val pila = ArrayList<Guardado>()

    val nivel: Int
        get() = pila.size

    fun rutaNavegacion(): String {
        val partes = ArrayList<String>()
        for (g in pila) partes.add(g.c.nombre)
        contenedor?.let { partes.add(it.nombre) }
        return partes.joinToString(" ▸ ")
    }

    fun notificar() {
        revision.value = revision.value + 1
    }

    fun cancelarApertura() {
        cancelarFlag = true
    }

    /** Uri original del contenedor de nivel 0 (para poder reanudar una extracción). */
    var origenUri: String? = null
        private set

    /** Solo se pregunta una vez por sesión si hay una extracción interrumpida. */
    var preguntoPendiente: Boolean = false

    /** El permiso de notificaciones se pide una sola vez por sesión (no en cada recreación). */
    var pidioNotificaciones: Boolean = false

    /** Origen reanudable: solo si el contenedor está en el nivel 0 y vino de una Uri. */
    val origenReanudable: String?
        get() = if (pila.isEmpty()) origenUri else null

    /** Abre un archivo elegido en la app (externo=false) o recibido de otra app (externo=true). */
    fun abrir(ctx: Context, uri: Uri, externo: Boolean, alTerminar: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        iniciarApertura(app, externo, uri.toString(), alTerminar) { max, prog, canc ->
            CatalogoMotores.abrir(app, uri, max, prog, canc)
        }
    }

    /** Abre un archivo dividido en volúmenes (varias partes elegidas a la vez). */
    fun abrirVarios(ctx: Context, uris: List<Uri>, alTerminar: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        iniciarApertura(app, true, null, alTerminar) { max, prog, canc ->
            CatalogoMotores.abrirVolumenes(app, uris, max, prog, canc)
        }
    }

    private fun iniciarApertura(
        app: Context,
        usarLimiteRecibido: Boolean,
        origen: String?,
        alTerminar: ((Boolean) -> Unit)?,
        abrirFn: suspend (Long, (Long, Long) -> Unit, () -> Boolean) -> Contenedor
    ) {
        if (cargando) return
        viewModelScope.launch {
            cargando = true
            error = null
            cancelarFlag = false
            var ok = false
            progreso.value = ProgresoApertura(true, 0L, -1L, "Copiando…")
            notificar()
            try {
                val p = PreferenciasApp(app)
                val max = if (usarLimiteRecibido) p.entero("max_recibido_mb").toLong() * 1024L * 1024L
                else Seguridad.limites(p).maxContenedorBytes
                val c = withContext(Dispatchers.IO) {
                    abrirFn(max, { h, t -> progreso.value = ProgresoApertura(true, h, t, "Copiando…") }, { cancelarFlag })
                }
                val arbol = withContext(Dispatchers.Default) { ArbolBuilder.construir(c.entradas) }
                cerrarTodo()
                contenedor = c
                raiz = arbol
                origenUri = origen
                p.sumar("stat_abiertos", 1L)
                if (origen != null) Historial.agregar(app, origen, c.nombre, c.tamanoArchivo)
                ok = true
            } catch (t: CopiaCancelada) {
                // cancelado por el usuario: sin mensaje
            } catch (t: Throwable) {
                error = Ui.mensajeError(t)
            } finally {
                cargando = false
                progreso.value = ProgresoApertura()
                notificar()
            }
            alTerminar?.invoke(ok)
        }
    }

    /** Abre como contenedor un archivo comprimido que está dentro del contenedor actual. */
    fun abrirAnidado(ctx: Context, entrada: Entrada) {
        val actual = contenedor ?: return
        val app = ctx.applicationContext
        if (cargando) return
        viewModelScope.launch {
            cargando = true
            error = null
            cancelarFlag = false
            progreso.value = ProgresoApertura(true, 0L, entrada.tamano, "Extrayendo " + entrada.nombre + "…")
            notificar()
            try {
                val lim = Seguridad.limites(PreferenciasApp(app))
                if (entrada.tamano > lim.maxEntradaBytes) {
                    throw IOException("La entrada supera el tamaño máximo por entrada (ajustable en Ajustes)")
                }
                val copia = withContext(Dispatchers.IO) {
                    copiarEntrada(app, actual, entrada, lim.maxEntradaBytes)
                }
                val c = try {
                    withContext(Dispatchers.IO) { CatalogoMotores.abrirArchivo(app, copia, entrada.nombre) }
                } catch (t: Throwable) {
                    copia.delete()
                    throw t
                }
                val arbol = withContext(Dispatchers.Default) { ArbolBuilder.construir(c.entradas) }
                pila.add(Guardado(actual, raiz, expandidos, seleccion, consulta, filtro, cambiosApk))
                contenedor = c
                raiz = arbol
                expandidos = HashSet()
                seleccion = HashSet()
                consulta = ""
                filtro = null
                cambiosApk = CambiosApk()
                PreferenciasApp(app).sumar("stat_abiertos", 1L)
            } catch (t: CopiaCancelada) {
                // cancelado
            } catch (t: Throwable) {
                error = Ui.mensajeError(t)
            } finally {
                cargando = false
                progreso.value = ProgresoApertura()
                notificar()
            }
        }
    }

    private fun copiarEntrada(app: Context, origen: Contenedor, entrada: Entrada, limite: Long): File {
        val destino = File(CopiaLocal.carpetaAbiertos(app), UUID.randomUUID().toString() + ".tmp")
        var ultimo = 0L
        try {
            origen.abrirEntrada(entrada).use { flujo ->
                val contador = ContadorInput(flujo, limite) { _ ->
                    val ahora = System.currentTimeMillis()
                    if (ahora - ultimo > 150L) {
                        ultimo = ahora
                    }
                }
                FileOutputStream(destino).use { o ->
                    val buf = ByteArray(PreferenciasApp(app).bufferBytes().coerceAtLeast(32 * 1024))
                    while (true) {
                        if (cancelarFlag) throw CopiaCancelada()
                        val n = contador.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        val ahora = System.currentTimeMillis()
                        if (ahora - ultimo > 150L) {
                            ultimo = ahora
                            progreso.value = ProgresoApertura(true, contador.total, entrada.tamano, "Extrayendo " + entrada.nombre + "…")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            destino.delete()
            throw t
        }
        return destino
    }

    private fun cerrarActual() {
        val c = contenedor
        if (c != null) {
            try { c.close() } catch (e: Exception) { }
            CopiaLocal.borrar(c.archivo)
        }
        cambiosApk.limpiar()
    }

    private fun cerrarTodo() {
        cerrarActual()
        while (pila.isNotEmpty()) {
            val g = pila.removeAt(pila.size - 1)
            try { g.c.close() } catch (e: Exception) { }
            CopiaLocal.borrar(g.c.archivo)
            g.cambios.limpiar()
        }
        contenedor = null
        raiz = null
        origenUri = null
        expandidos = HashSet()
        seleccion = HashSet()
        consulta = ""
        filtro = null
        cambiosApk = CambiosApk()
    }

    /** Si hay un contenedor padre, vuelve a él; si no, cierra todo. */
    fun cerrar() {
        if (pila.isEmpty()) {
            cerrarTodo()
            error = null
        } else {
            cerrarActual()
            val g = pila.removeAt(pila.size - 1)
            contenedor = g.c
            raiz = g.raiz
            expandidos = g.expandidos
            seleccion = g.seleccion
            consulta = g.consulta
            filtro = g.filtro
            cambiosApk = g.cambios
        }
        notificar()
    }

    fun visibles(): List<Nodo> {
        val r = raiz ?: return emptyList()
        val q = consulta.trim().lowercase()
        val f = filtro
        val salida = ArrayList<Nodo>()
        if (q.isEmpty() && f == null) {
            fun visitar(n: Nodo) {
                for (h in n.hijos) {
                    salida.add(h)
                    if (h.esDir && h.ruta in expandidos) visitar(h)
                }
            }
            visitar(r)
        } else {
            for (h in r.archivos) {
                if (f != null && h.entrada?.categoria != f) continue
                if (q.isNotEmpty() && !h.ruta.lowercase().contains(q)) continue
                salida.add(h)
            }
        }
        return salida
    }

    /** 0 = ninguno, 1 = parcial, 2 = todos */
    fun estadoSeleccion(n: Nodo): Int {
        if (!n.esDir) return if (n.entrada != null && n.entrada.ruta in seleccion) 2 else 0
        val hojas = n.archivos
        if (hojas.isEmpty()) return 0
        var marcadas = 0
        for (h in hojas) if (h.entrada != null && h.entrada.ruta in seleccion) marcadas++
        return when (marcadas) {
            0 -> 0
            hojas.size -> 2
            else -> 1
        }
    }

    fun alternar(n: Nodo) {
        if (!n.esDir) {
            val e = n.entrada ?: return
            if (!seleccion.remove(e.ruta)) seleccion.add(e.ruta)
        } else {
            val todos = estadoSeleccion(n) == 2
            for (h in n.archivos) {
                val e = h.entrada ?: continue
                if (todos) seleccion.remove(e.ruta) else seleccion.add(e.ruta)
            }
        }
        notificar()
    }

    fun marcarTodo() {
        val r = raiz ?: return
        for (h in r.archivos) h.entrada?.let { seleccion.add(it.ruta) }
        notificar()
    }

    fun desmarcarTodo() {
        seleccion.clear()
        notificar()
    }

    fun alternarExpandido(n: Nodo) {
        if (!expandidos.remove(n.ruta)) expandidos.add(n.ruta)
        notificar()
    }

    fun totalArchivos(): Int = raiz?.archivos?.size ?: 0

    override fun onCleared() {
        cerrarTodo()
        super.onCleared()
    }
}
