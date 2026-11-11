package net.murilo.scanner

import android.content.Context
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Semeia UMA sessão de exemplo (se não houver nenhuma) só pra visualizar o Registro antes de rodar no carro. */
object DemoTrip {
    fun seedIfEmpty(ctx: Context) {
        try {
            val dir = File(ctx.filesDir, "trips").apply { mkdirs() }
            // já existe uma sessão de verdade (com dados)? então não semeia
            if (dir.listFiles { f -> f.name.endsWith(".csv") && f.length() > 200 }?.isNotEmpty() == true) return
            // limpa sessões vazias (só cabeçalho, sobra de app morto no meio)
            dir.listFiles { f -> f.name.endsWith(".csv") }?.forEach { if (it.length() <= 200) it.delete() }
            val start = System.currentTimeMillis() - 11 * 60 * 1000
            File(dir, "trip_$start.csv").bufferedWriter().use { w ->
                w.write(TripLogger.HEADER); w.newLine()
                var lat = -22.9919; var lon = -46.8357
                val n = 1500  // ~5 min a 5 Hz
                for (i in 0 until n) {
                    val t = start + i * 200L
                    val ph = i.toDouble() / n
                    val speed = max(0.0, (sin(ph * Math.PI * 3) * 0.5 + 0.5) * 92 + sin(i / 15.0) * 6)
                    val rpm = 900 + speed * 34 + sin(i / 8.0) * 300
                    val gear = when { speed < 4 -> 0; speed < 20 -> 1; speed < 40 -> 2; speed < 62 -> 3; speed < 88 -> 4; else -> 5 }
                    val ect = min(92.0, 68 + ph * 26)
                    val tps = max(0.0, min(100.0, speed * 0.85 + sin(i / 5.0) * 18))
                    val map = 20 + tps * 0.6
                    val lambda = 0.95 + sin(i / 10.0) * 0.08
                    lat += 0.00006 * sin(ph * Math.PI * 2); lon += 0.00006 * Math.cos(ph * Math.PI * 2)
                    w.write("%d,%.6f,%.6f,%.0f,%.0f,%d,%.0f,%.0f,%.0f,%.0f,%.1f,%.2f,%.2f,%.2f,%.0f,%.1f".format(
                        java.util.Locale.US,
                        t, lat, lon, rpm, speed, gear, ect, 32.0, map, tps, 13.8, 2.5 + tps / 30, lambda, speed * 0.3, 70.0, 11.0 + sin(i / 20.0) * 4))
                    w.newLine()
                }
            }
        } catch (_: Exception) {}
    }
}
