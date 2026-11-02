package com.carpe.descompresor.data

import com.carpe.descompresor.util.Iconos

data class Entrada(
    val ruta: String,
    val esDirectorio: Boolean,
    val tamano: Long,            // -1 = desconocido
    val tamanoComprimido: Long,  // -1 = desconocido
    val metodo: String,
    val crc: Long,               // -1 = desconocido
    val fecha: Long,             // ms; 0 = desconocida
    val cifrada: Boolean = false,
    val atributos: String = ""
) {
    val nombre: String
        get() = ruta.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    val categoria: Iconos.Categoria
        get() = Iconos.categoria(nombre)
}
