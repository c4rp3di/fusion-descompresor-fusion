package com.carpe.descompresor.motor

enum class TipoFormato(val etiqueta: String) {
    ZIP("ZIP"),
    APK("APK"),
    GZIP("GZIP"),
    TAR_GZ("TAR.GZ"),
    TAR("TAR"),
    BZIP2("BZIP2"),
    TAR_BZ2("TAR.BZ2"),
    XZ("XZ"),
    TAR_XZ("TAR.XZ"),
    SIETE_Z("7z"),
    RAR("RAR"),
    CPIO("CPIO"),
    AR("AR"),
    DESCONOCIDO("Desconocido")
}

object DetectorFormato {
    private fun empieza(b: ByteArray, vararg v: Int): Boolean {
        if (b.size < v.size) return false
        for (i in v.indices) if ((b[i].toInt() and 0xFF) != v[i]) return false
        return true
    }

    private fun ascii(b: ByteArray, off: Int, texto: String): Boolean {
        if (b.size < off + texto.length) return false
        for (i in texto.indices) if (b[off + i].toInt() != texto[i].code) return false
        return true
    }

    fun detectar(cab: ByteArray, nombre: String): TipoFormato {
        val n = nombre.lowercase()
        if (empieza(cab, 0x50, 0x4B) && cab.size >= 4 &&
            ((cab[2].toInt() == 3 && cab[3].toInt() == 4) ||
                (cab[2].toInt() == 5 && cab[3].toInt() == 6) ||
                (cab[2].toInt() == 7 && cab[3].toInt() == 8))
        ) {
            return if (n.endsWith(".apk")) TipoFormato.APK else TipoFormato.ZIP
        }
        if (empieza(cab, 0x1F, 0x8B)) {
            return if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) TipoFormato.TAR_GZ else TipoFormato.GZIP
        }
        if (empieza(cab, 0x42, 0x5A, 0x68)) {
            return if (n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz")) TipoFormato.TAR_BZ2 else TipoFormato.BZIP2
        }
        if (empieza(cab, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) {
            return if (n.endsWith(".tar.xz") || n.endsWith(".txz")) TipoFormato.TAR_XZ else TipoFormato.XZ
        }
        if (empieza(cab, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return TipoFormato.SIETE_Z
        if (empieza(cab, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) return TipoFormato.RAR
        if (ascii(cab, 0, "!<arch>\n")) return TipoFormato.AR
        if (ascii(cab, 257, "ustar")) return TipoFormato.TAR
        if (ascii(cab, 0, "070701") || ascii(cab, 0, "070702") || ascii(cab, 0, "070707") ||
            empieza(cab, 0xC7, 0x71) || empieza(cab, 0x71, 0xC7)
        ) return TipoFormato.CPIO
        if (n.endsWith(".tar")) return TipoFormato.TAR
        return TipoFormato.DESCONOCIDO
    }
}
