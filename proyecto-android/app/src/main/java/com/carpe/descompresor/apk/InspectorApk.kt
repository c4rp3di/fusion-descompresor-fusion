package com.carpe.descompresor.apk

import com.carpe.descompresor.data.ApkMetadata
import com.carpe.descompresor.data.ComponenteApk
import java.io.File
import java.util.zip.ZipFile

object InspectorApk {
    class Manifest(val xml: String, val metadatos: ApkMetadata)

    fun leerManifestBytes(apk: File): ByteArray {
        ZipFile(apk).use { z ->
            val e = z.getEntry("AndroidManifest.xml") ?: throw IllegalStateException("El APK no contiene AndroidManifest.xml")
            return z.getInputStream(e).use { it.readBytes() }
        }
    }

    fun leer(apk: File): Manifest {
        val r = ParserAxml.parsear(leerManifestBytes(apk))
        val m = r.raiz ?: throw IllegalStateException("Manifest vacío")
        val usesSdk = m.hijos.firstOrNull { it.tag == "uses-sdk" }
        val app = m.hijos.firstOrNull { it.tag == "application" }
        val permisos = m.hijos
            .filter { it.tag == "uses-permission" || it.tag == "uses-permission-sdk-23" }
            .mapNotNull { it.atributos["android:name"] }
        val tiposComponente = setOf("activity", "activity-alias", "service", "receiver", "provider")
        val comps = ArrayList<ComponenteApk>()
        if (app != null) {
            for (h in app.hijos) {
                if (h.tag in tiposComponente) {
                    comps.add(ComponenteApk(h.tag, h.atributos["android:name"] ?: "?", h.atributos["android:exported"] ?: "—"))
                }
            }
        }
        val meta = ApkMetadata(
            paquete = m.atributos["package"] ?: "?",
            versionNombre = m.atributos["android:versionName"] ?: "?",
            versionCodigo = m.atributos["android:versionCode"] ?: "?",
            minSdk = usesSdk?.atributos?.get("android:minSdkVersion") ?: "—",
            targetSdk = usesSdk?.atributos?.get("android:targetSdkVersion") ?: "—",
            etiqueta = app?.atributos?.get("android:label") ?: "—",
            icono = app?.atributos?.get("android:icon") ?: "—",
            permisos = permisos,
            componentes = comps
        )
        return Manifest(r.xml, meta)
    }

    fun minSdkNumerico(m: ApkMetadata): Int? = m.minSdk.toIntOrNull()
}
