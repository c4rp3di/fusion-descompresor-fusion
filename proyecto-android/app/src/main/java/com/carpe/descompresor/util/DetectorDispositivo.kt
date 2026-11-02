package com.carpe.descompresor.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build

object DetectorDispositivo {
    data class Info(
        val abi: String,
        val nucleos: Int,
        val ramTotalMb: Long,
        val ramLibreMb: Long,
        val lowRam: Boolean,
        val sdk: Int
    )

    fun info(ctx: Context): Info {
        val am = ctx.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val abi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "desconocida"
        return Info(
            abi = abi,
            nucleos = Runtime.getRuntime().availableProcessors(),
            ramTotalMb = mi.totalMem / (1024L * 1024L),
            ramLibreMb = mi.availMem / (1024L * 1024L),
            lowRam = am.isLowRamDevice,
            sdk = Build.VERSION.SDK_INT
        )
    }

    fun recomendados(ctx: Context): Map<String, Any> {
        val i = info(ctx)
        val m = LinkedHashMap<String, Any>()
        if (i.lowRam) {
            m["buffer_kb"] = 32
            m["max_contenedor_mb"] = 200
            m["max_entrada_mb"] = 100
            m["max_total_mb"] = 1024
            m["prev_habilitada"] = false
            m["prev_imagen_mp"] = 4
            m["prev_texto_kb"] = 200
        } else {
            m["buffer_kb"] = if (i.nucleos >= 4) 256 else 64
            m["max_contenedor_mb"] = 500
            m["max_entrada_mb"] = 200
            m["max_total_mb"] = 2048
            m["prev_habilitada"] = true
            m["prev_imagen_mp"] = 8
            m["prev_texto_kb"] = 500
        }
        return m
    }

    fun resumen(ctx: Context): String {
        val i = info(ctx)
        return "Android SDK ${i.sdk} · ABI ${i.abi} · ${i.nucleos} núcleos · RAM ${i.ramTotalMb} MB " +
            "(libre ${i.ramLibreMb} MB)" + if (i.lowRam) " · dispositivo de poca RAM" else ""
    }
}
