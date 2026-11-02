package com.carpe.descompresor.data

data class ComponenteApk(
    val tipo: String,
    val nombre: String,
    val exportado: String
)

data class ApkMetadata(
    val paquete: String,
    val versionNombre: String,
    val versionCodigo: String,
    val minSdk: String,
    val targetSdk: String,
    val etiqueta: String,
    val icono: String,
    val permisos: List<String>,
    val componentes: List<ComponenteApk>
)
