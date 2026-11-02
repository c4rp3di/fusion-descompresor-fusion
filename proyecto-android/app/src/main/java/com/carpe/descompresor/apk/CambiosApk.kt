package com.carpe.descompresor.apk

import java.io.File

/** Cambios pendientes sobre el ZIP/APK abierto (se aplican al guardar o reempaquetar). */
class CambiosApk {
    /** ruta original (o nueva ruta, si es un archivo añadido) -> archivo con el contenido nuevo */
    val reemplazos = LinkedHashMap<String, File>()
    val eliminados = LinkedHashSet<String>()
    /** ruta original -> ruta nueva */
    val renombres = LinkedHashMap<String, String>()

    fun total(): Int = reemplazos.size + eliminados.size + renombres.size
    fun vacio(): Boolean = total() == 0

    fun limpiar() {
        reemplazos.values.forEach { it.delete() }
        reemplazos.clear()
        eliminados.clear()
        renombres.clear()
    }
}
