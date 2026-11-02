package com.carpe.descompresor.destino

import android.content.Context
import java.io.InputStream

interface EstrategiaDestino {
    val id: String
    val nombre: String
    fun disponible(ctx: Context): Boolean
    suspend fun elegir(ctx: Context): Boolean
    fun descripcion(): String
    suspend fun existe(ctx: Context, rutaRelativa: String): Boolean
    suspend fun escribir(ctx: Context, rutaRelativa: String, input: InputStream): Boolean
    suspend fun eliminar(ctx: Context, rutaRelativa: String): Boolean
}
