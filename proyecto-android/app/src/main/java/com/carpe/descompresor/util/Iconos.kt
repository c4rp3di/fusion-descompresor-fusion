package com.carpe.descompresor.util

object Iconos {
    enum class Categoria(val etiqueta: String) {
        IMAGEN("Imágenes"),
        VIDEO("Vídeo"),
        AUDIO("Audio"),
        TEXTO("Texto"),
        CODIGO("Código"),
        PDF("PDF"),
        APK("APK"),
        COMPRIMIDO("Comprimidos"),
        BINARIO("Binarios"),
        DESCONOCIDO("Desconocidos")
    }

    private val imagen = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "heic", "avif")
    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv")
    private val audio = setOf("mp3", "ogg", "wav", "flac", "m4a", "aac", "opus", "amr", "mid")
    private val texto = setOf("txt", "md", "csv", "log", "ini", "cfg", "conf", "properties", "rtf", "srt")
    private val codigo = setOf(
        "kt", "java", "js", "ts", "py", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bat",
        "html", "htm", "css", "xml", "json", "yml", "yaml", "toml", "gradle", "smali", "sql", "kts"
    )
    private val comprimido = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "cpio", "ar", "deb", "jar", "apks", "xapk", "apkm")
    private val binario = setOf("so", "dex", "bin", "dat", "arsc", "class", "o", "exe", "dll", "db", "sqlite")

    fun extension(nombre: String): String {
        val n = nombre.trimEnd('/').substringAfterLast('/')
        val i = n.lastIndexOf('.')
        return if (i < 0 || i == n.length - 1) "" else n.substring(i + 1).lowercase()
    }

    fun categoria(nombre: String): Categoria {
        val e = extension(nombre)
        return when {
            e == "apk" -> Categoria.APK
            e == "pdf" -> Categoria.PDF
            e in imagen -> Categoria.IMAGEN
            e in video -> Categoria.VIDEO
            e in audio -> Categoria.AUDIO
            e in texto -> Categoria.TEXTO
            e in codigo -> Categoria.CODIGO
            e in comprimido -> Categoria.COMPRIMIDO
            e in binario -> Categoria.BINARIO
            else -> Categoria.DESCONOCIDO
        }
    }

    fun emoji(categoria: Categoria, esDir: Boolean): String {
        if (esDir) return "📁"
        return when (categoria) {
            Categoria.IMAGEN -> "🖼"
            Categoria.VIDEO -> "🎞"
            Categoria.AUDIO -> "🎵"
            Categoria.TEXTO -> "📄"
            Categoria.CODIGO -> "🧾"
            Categoria.PDF -> "📕"
            Categoria.APK -> "🤖"
            Categoria.COMPRIMIDO -> "🗜"
            Categoria.BINARIO -> "⚙"
            Categoria.DESCONOCIDO -> "▫"
        }
    }
}
