package com.carpe.descompresor.apk

import com.android.apksig.ApkVerifier
import com.carpe.descompresor.util.Formato
import java.io.File
import java.security.MessageDigest

data class InfoFirma(
    val verificado: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val huellasSha256: List<String>,
    val sujetos: List<String>,
    val autofirmado: Boolean,
    val errores: List<String>,
    val avisos: List<String>
) {
    fun texto(): String {
        val sb = StringBuilder()
        sb.append(if (verificado) "✔ Firma válida\n" else "✘ Firma NO válida o ausente\n")
        sb.append("Esquemas: v1=${if (v1) "sí" else "no"} · v2=${if (v2) "sí" else "no"} · v3=${if (v3) "sí" else "no"}\n")
        for (i in huellasSha256.indices) {
            sb.append("\nCertificado ${i + 1}\n")
            if (i < sujetos.size) sb.append(sujetos[i]).append('\n')
            sb.append("SHA-256: ").append(huellasSha256[i]).append('\n')
        }
        if (huellasSha256.isNotEmpty()) sb.append("\nAutofirmado: ").append(if (autofirmado) "sí" else "no").append('\n')
        if (errores.isNotEmpty()) {
            sb.append("\nErrores:\n")
            for (e in errores.take(20)) sb.append("• ").append(e).append('\n')
        }
        if (avisos.isNotEmpty()) {
            sb.append("\nAvisos:\n")
            for (e in avisos.take(20)) sb.append("• ").append(e).append('\n')
        }
        return sb.toString()
    }
}

object LectorFirma {
    fun huella(cert: java.security.cert.X509Certificate): String {
        val d = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return Formato.hexConDosPuntos(d)
    }

    fun leer(archivo: File): InfoFirma {
        return try {
            val r = ApkVerifier.Builder(archivo).build().verify()
            val certs = r.signerCertificates
            val huellas = certs.map { huella(it) }
            val sujetos = certs.map { it.subjectX500Principal.name }
            val auto = certs.isNotEmpty() && certs.all { it.subjectX500Principal == it.issuerX500Principal }
            val errores = r.errors.map { it.toString() }
            val avisos = r.warnings.map { it.toString() }
            InfoFirma(
                r.isVerified,
                r.isVerifiedUsingV1Scheme,
                r.isVerifiedUsingV2Scheme,
                r.isVerifiedUsingV3Scheme,
                huellas, sujetos, auto,
                if (errores.isEmpty() && !r.isVerified) listOf("El APK no tiene una firma verificable") else errores,
                avisos
            )
        } catch (e: Exception) {
            InfoFirma(false, false, false, false, emptyList(), emptyList(), false,
                listOf(e.message ?: e.javaClass.simpleName), emptyList())
        }
    }
}
