package com.carpe.descompresor.apk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

class ClaveFirma(val alias: String, val clave: PrivateKey, val certificados: List<X509Certificate>)

class InfoKeystore(val id: String, val nombre: String, val alias: String)

/** Origen de claves: PKCS12/BKS vía KeyStore de Android, o JKS clásico con lector propio (Android no trae JKS). */
interface AlmacenClaves {
    fun aliases(): List<String>
    fun clave(alias: String, passClave: CharArray): ClaveFirma
}

private class AlmacenJava(private val ks: KeyStore) : AlmacenClaves {
    override fun aliases(): List<String> = ks.aliases().toList().filter { ks.isKeyEntry(it) }

    override fun clave(alias: String, passClave: CharArray): ClaveFirma {
        val k = ks.getKey(alias, passClave) as? PrivateKey ?: throw IOException("No se pudo leer la clave «$alias» (¿contraseña de clave incorrecta?)")
        val cadena = ks.getCertificateChain(alias) ?: throw IOException("El alias «$alias» no tiene cadena de certificados")
        return ClaveFirma(alias, k, cadena.map { it as X509Certificate })
    }
}

private class AlmacenJks(archivo: File) : AlmacenClaves {
    private class EntradaJks(val alias: String, val claveCifrada: ByteArray, val cadena: List<ByteArray>)

    private val entradas = ArrayList<EntradaJks>()

    init {
        DataInputStream(BufferedInputStream(FileInputStream(archivo))).use { d ->
            if (d.readInt() != 0xFEEDFEED.toInt()) throw IOException("No es un keystore JKS")
            val version = d.readInt()
            if (version != 1 && version != 2) throw IOException("Versión JKS no soportada: $version")
            val n = d.readInt()
            for (i in 0 until n) {
                val tag = d.readInt()
                val alias = d.readUTF()
                d.readLong()
                if (tag == 1) {
                    val kb = ByteArray(d.readInt())
                    d.readFully(kb)
                    val nc = d.readInt()
                    val cadena = ArrayList<ByteArray>()
                    for (j in 0 until nc) {
                        if (version == 2) d.readUTF()
                        val cb = ByteArray(d.readInt())
                        d.readFully(cb)
                        cadena.add(cb)
                    }
                    entradas.add(EntradaJks(alias, kb, cadena))
                } else if (tag == 2) {
                    if (version == 2) d.readUTF()
                    val cb = ByteArray(d.readInt())
                    d.readFully(cb)
                } else {
                    throw IOException("Entrada JKS desconocida")
                }
            }
        }
    }

    override fun aliases(): List<String> = entradas.map { it.alias }

    override fun clave(alias: String, passClave: CharArray): ClaveFirma {
        val e = entradas.firstOrNull { it.alias == alias } ?: throw IOException("Alias no encontrado: $alias")
        val pkcs8 = descifrar(extraerOctetos(e.claveCifrada), passClave)
        var clave: PrivateKey? = null
        for (alg in listOf("RSA", "EC", "DSA")) {
            try {
                clave = KeyFactory.getInstance(alg).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                break
            } catch (x: Exception) {
                // probar el siguiente algoritmo
            }
        }
        val k = clave ?: throw IOException("Tipo de clave no soportado")
        val cf = CertificateFactory.getInstance("X.509")
        val certs = e.cadena.map { cf.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
        return ClaveFirma(alias, k, certs)
    }

    /** EncryptedPrivateKeyInfo = SEQUENCE { SEQUENCE { OID, NULL }, OCTET STRING } */
    private fun extraerOctetos(der: ByteArray): ByteArray {
        var p = 0
        fun longitud(): Int {
            var l = der[p++].toInt() and 0xFF
            if ((l and 0x80) != 0) {
                val n = l and 0x7F
                l = 0
                for (i in 0 until n) l = (l shl 8) or (der[p++].toInt() and 0xFF)
            }
            return l
        }
        if (der[p++].toInt() != 0x30) throw IOException("Clave JKS con formato inesperado")
        longitud()
        if (der[p++].toInt() != 0x30) throw IOException("Clave JKS con formato inesperado")
        val l2 = longitud()
        p += l2
        if (der[p++].toInt() != 0x04) throw IOException("Clave JKS con formato inesperado")
        val l3 = longitud()
        return der.copyOfRange(p, p + l3)
    }

    /** Protector de claves de Sun: salt(20) | datos XOR flujo SHA-1 | SHA-1(pass||claro). */
    private fun descifrar(data: ByteArray, pass: CharArray): ByteArray {
        val tam = data.size - 40
        if (tam <= 0) throw IOException("Clave JKS corrupta")
        val salt = data.copyOfRange(0, 20)
        val cifrado = data.copyOfRange(20, 20 + tam)
        val check = data.copyOfRange(20 + tam, data.size)
        val pw = ByteArray(pass.size * 2)
        for (i in pass.indices) {
            pw[2 * i] = (pass[i].code shr 8).toByte()
            pw[2 * i + 1] = pass[i].code.toByte()
        }
        val md = MessageDigest.getInstance("SHA-1")
        val claro = ByteArray(tam)
        var digest = salt
        var off = 0
        while (off < tam) {
            md.update(pw)
            md.update(digest)
            digest = md.digest()
            val n = minOf(20, tam - off)
            for (i in 0 until n) claro[off + i] = (cifrado[off + i].toInt() xor digest[i].toInt()).toByte()
            off += n
        }
        md.update(pw)
        md.update(claro)
        if (!MessageDigest.isEqual(md.digest(), check)) throw IOException("Contraseña de clave incorrecta")
        return claro
    }
}

object GestorKeystore {

    fun abrirAlmacen(archivo: File, passStore: CharArray): AlmacenClaves {
        val cab = ByteArray(4)
        FileInputStream(archivo).use { it.read(cab) }
        val esJks = (cab[0].toInt() and 0xFF) == 0xFE && (cab[1].toInt() and 0xFF) == 0xED &&
            (cab[2].toInt() and 0xFF) == 0xFE && (cab[3].toInt() and 0xFF) == 0xED
        if (esJks) return AlmacenJks(archivo)
        var ultimo: Exception? = null
        for (tipo in listOf("PKCS12", "BKS")) {
            try {
                val ks = KeyStore.getInstance(tipo)
                FileInputStream(archivo).use { ks.load(it, passStore) }
                return AlmacenJava(ks)
            } catch (e: Exception) {
                ultimo = e
            }
        }
        throw IOException("No se pudo abrir el keystore (¿contraseña incorrecta o formato no soportado?): " + (ultimo?.message ?: ""))
    }

    fun copiarTemporal(ctx: Context, uri: Uri): File {
        val d = File(ctx.cacheDir, "ks_tmp")
        d.mkdirs()
        val f = File(d, "ks_" + System.currentTimeMillis())
        val i = ctx.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el keystore")
        i.use { s -> f.outputStream().use { o -> s.copyTo(o) } }
        return f
    }

    // ---- Persistencia (contraseñas cifradas con EncryptedSharedPreferences) ----
    private fun seguras(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        val mk = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, "keystores_seguro", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun carpeta(ctx: Context): File {
        val d = File(ctx.filesDir, "keystores")
        d.mkdirs()
        return d
    }

    fun listar(ctx: Context): List<InfoKeystore> {
        val sp = seguras(ctx)
        val ids = (sp.getString("ids", "") ?: "").split(',').filter { it.isNotBlank() }
        return ids.map { InfoKeystore(it, sp.getString("nombre_$it", it) ?: it, sp.getString("alias_$it", "") ?: "") }
    }

    fun activa(ctx: Context): String? = seguras(ctx).getString("activa", null)

    fun seleccionar(ctx: Context, id: String) {
        seguras(ctx).edit().putString("activa", id).apply()
    }

    /** Valida la clave y guarda el keystore (copia privada + contraseñas cifradas). */
    fun guardar(ctx: Context, temporal: File, nombre: String, alias: String, passStore: String, passClave: String): InfoKeystore {
        val almacen = abrirAlmacen(temporal, passStore.toCharArray())
        almacen.clave(alias, passClave.toCharArray())  // lanza si no es válida
        val id = "ks" + System.currentTimeMillis()
        temporal.copyTo(File(carpeta(ctx), "$id.ks"), overwrite = true)
        temporal.delete()
        val sp = seguras(ctx)
        val ids = (sp.getString("ids", "") ?: "").split(',').filter { it.isNotBlank() }.toMutableList()
        ids.add(id)
        sp.edit()
            .putString("ids", ids.joinToString(","))
            .putString("nombre_$id", nombre)
            .putString("alias_$id", alias)
            .putString("ps_$id", passStore)
            .putString("pk_$id", passClave)
            .putString("activa", id)
            .apply()
        return InfoKeystore(id, nombre, alias)
    }

    fun eliminar(ctx: Context, id: String) {
        File(carpeta(ctx), "$id.ks").delete()
        val sp = seguras(ctx)
        val ids = (sp.getString("ids", "") ?: "").split(',').filter { it.isNotBlank() && it != id }
        val e = sp.edit()
            .putString("ids", ids.joinToString(","))
            .remove("nombre_$id").remove("alias_$id").remove("ps_$id").remove("pk_$id")
        if (sp.getString("activa", null) == id) e.remove("activa")
        e.apply()
    }

    fun cargarClave(ctx: Context, id: String): ClaveFirma {
        val sp = seguras(ctx)
        val alias = sp.getString("alias_$id", null) ?: throw IOException("Keystore no encontrado")
        val ps = sp.getString("ps_$id", "") ?: ""
        val pk = sp.getString("pk_$id", "") ?: ""
        val almacen = abrirAlmacen(File(carpeta(ctx), "$id.ks"), ps.toCharArray())
        return almacen.clave(alias, pk.toCharArray())
    }
}
