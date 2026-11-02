package com.carpe.descompresor.apk

import com.android.apksig.ApkSigner
import java.io.File

object FirmadorApk {
    fun firmar(entrada: File, salida: File, clave: ClaveFirma, v1: Boolean, v2: Boolean, v3: Boolean, minSdk: Int?) {
        if (!v1 && !v2 && !v3) throw IllegalArgumentException("Activa al menos un esquema de firma (v1, v2 o v3)")
        if (salida.exists()) salida.delete()
        val nombreSeguro = clave.alias.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "CERT" }
        val cfg = ApkSigner.SignerConfig.Builder(nombreSeguro, clave.clave, clave.certificados).build()
        val b = ApkSigner.Builder(listOf(cfg))
            .setInputApk(entrada)
            .setOutputApk(salida)
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(v2)
            .setV3SigningEnabled(v3)
        if (minSdk != null) b.setMinSdkVersion(minSdk)
        b.build().sign()
    }
}
