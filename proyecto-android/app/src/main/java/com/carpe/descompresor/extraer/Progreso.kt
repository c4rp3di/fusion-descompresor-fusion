package com.carpe.descompresor.extraer

import android.net.Uri
import com.carpe.descompresor.data.Contenedor
import kotlinx.coroutines.flow.MutableStateFlow

enum class SiExiste { SOBRESCRIBIR, RENOMBRAR, OMITIR }

data class OpcionesExtraccion(
    val preservarEstructura: Boolean,
    val siExiste: SiExiste,
    val verificarCrc: Boolean,
    val prefijo: String = "",          // subcarpeta de destino (lotes: una por archivo)
    val reanudar: Boolean = false,     // reanudación: lo que ya existe se da por extraído
    val enCurso: String = ""           // archivo que se estaba escribiendo al interrumpirse (se rehace)
)

class SolicitudExtraccion(
    val contenedor: Contenedor?,
    val rutas: Set<String>,
    val destinoId: String,
    val opciones: OpcionesExtraccion,
    val lote: List<Uri>? = null,
    val origen: String? = null,        // Uri original del contenedor (para poder reanudar)
    val destinoDetalle: String = ""    // p. ej. carpeta interna ya creada
)

data class Progreso(
    val activo: Boolean = false,
    val archivoActual: String = "",
    val hechos: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0L,
    val bytesTotal: Long = 0L,
    val informe: InformeExtraccion? = null,
    val mensaje: String = ""
)

object EstadoExtraccion {
    val estado = MutableStateFlow(Progreso())

    @Volatile
    var cancelar: Boolean = false

    @Volatile
    var solicitud: SolicitudExtraccion? = null
}
