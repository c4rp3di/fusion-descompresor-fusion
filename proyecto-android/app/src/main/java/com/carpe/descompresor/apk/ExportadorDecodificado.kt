package com.carpe.descompresor.apk

import com.carpe.descompresor.util.Flujos
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Exporta un APK a un ZIP "decodificado": copia todos los archivos y convierte a XML legible el
 * Manifest y los XML de res/. No toca resources.arsc, el código (.dex), imágenes ni librerías.
 */
object ExportadorDecodificado {
    class Resumen(val archivos: Int, val xmlDecodificados: Int, val xmlSinDecodificar: List<String>)

    private const val LIMITE_XML = 8 * 1024 * 1024

    fun exportar(origen: File, salida: OutputStream): Resumen {
        var total = 0
        var decodificados = 0
        val sinDecodificar = ArrayList<String>()
        ZipFile(origen).use { z ->
            ZipOutputStream(salida.buffered()).use { out ->
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.isDirectory) continue
                    val n = e.name
                    val esXml = n == "AndroidManifest.xml" || (n.startsWith("res/") && n.endsWith(".xml"))
                    try {
                        if (esXml && e.size in 0..LIMITE_XML.toLong()) {
                            val bytes = z.getInputStream(e).use { it.readBytes() }
                            var texto: ByteArray? = null
                            if (ParserAxml.esAxml(bytes)) {
                                try {
                                    texto = ParserAxml.parsear(bytes).xml.toByteArray(Charsets.UTF_8)
                                } catch (x: Exception) {
                                    sinDecodificar.add(n)
                                }
                            }
                            out.putNextEntry(ZipEntry(n))
                            if (texto != null) {
                                out.write(texto)
                                decodificados++
                            } else {
                                out.write(bytes)
                            }
                            out.closeEntry()
                        } else {
                            out.putNextEntry(ZipEntry(n))
                            z.getInputStream(e).use { Flujos.copiar(it, out, 64 * 1024) }
                            out.closeEntry()
                        }
                        total++
                    } catch (x: ZipException) {
                        // nombre duplicado u otra incidencia con esta entrada: se omite
                        sinDecodificar.add("$n (omitido: ${x.message})")
                    }
                }
                val leeme = StringBuilder()
                leeme.append("APK decodificado con Descompresor\n\n")
                leeme.append("Archivos copiados: $total\n")
                leeme.append("XML convertidos a texto legible (Manifest y res/): $decodificados\n\n")
                leeme.append("Sin decodificar (se dejan tal cual):\n")
                leeme.append("- resources.arsc (tabla de recursos: strings, colores, estilos)\n")
                leeme.append("- classes*.dex (código)\n")
                leeme.append("- imágenes, assets/ y librerías nativas\n")
                if (sinDecodificar.isNotEmpty()) {
                    leeme.append("\nXML que no se pudieron decodificar o entradas omitidas:\n")
                    for (s in sinDecodificar) leeme.append("- ").append(s).append('\n')
                }
                leeme.append("\nNota: este ZIP es para consultar. No se puede recompilar directamente.\n")
                out.putNextEntry(ZipEntry("LEEME-decodificado.txt"))
                out.write(leeme.toString().toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return Resumen(total, decodificados, sinDecodificar)
    }
}
