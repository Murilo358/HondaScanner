package net.murilo.scanner

import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TripSample(
    val t: Long, val lat: Double, val lon: Double, val rpm: Float, val speed: Float, val gear: Int,
    val ect: Float, val iat: Float, val map: Float, val tps: Float, val battery: Float,
    val injDur: Float, val lambda: Float, val fuelFlow: Float, val fuelPct: Float, val economy: Float
)

class Trip(val file: File, val samples: List<TripSample>) {
    val startMs: Long get() = samples.firstOrNull()?.t ?: 0L
    val durationMs: Long get() = if (samples.isEmpty()) 0L else samples.last().t - samples.first().t
    val maxRpm: Float get() = samples.maxOfOrNull { it.rpm } ?: 0f
    val maxSpeed: Float get() = samples.maxOfOrNull { it.speed } ?: 0f
    val maxEct: Float get() = samples.maxOfOrNull { it.ect } ?: 0f
    val hasGps: Boolean get() = samples.any { it.lat != 0.0 || it.lon != 0.0 }

    val distanceKm: Float by lazy {
        var d = 0.0; var prev: TripSample? = null
        for (s in samples) {
            if (s.lat == 0.0 && s.lon == 0.0) { continue }
            prev?.let { d += haversine(it.lat, it.lon, s.lat, s.lon) }
            prev = s
        }
        (d / 1000.0).toFloat()
    }

    val fuelL: Float by lazy {   // integra a vazao (L/h) ao longo do tempo
        var l = 0.0; var prev: TripSample? = null
        for (s in samples) { prev?.let { l += s.fuelFlow * (s.t - it.t) / 3_600_000.0 }; prev = s }
        l.toFloat()
    }
    val avgKmL: Float get() = if (fuelL > 0.01f) distanceKm / fuelL else 0f

    companion object {
        fun load(f: File): Trip {
            val out = ArrayList<TripSample>()
            try {
                f.bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { ln ->
                        val p = ln.split(",")
                        if (p.size >= 16) {
                            fun fl(i: Int) = p[i].toFloatOrNull() ?: 0f
                            out.add(TripSample(
                                p[0].toLongOrNull() ?: 0L, p[1].toDoubleOrNull() ?: 0.0, p[2].toDoubleOrNull() ?: 0.0,
                                fl(3), fl(4), p[5].toIntOrNull() ?: 0, fl(6), fl(7), fl(8), fl(9), fl(10),
                                fl(11), fl(12), fl(13), fl(14), fl(15)
                            ))
                        }
                    }
                }
            } catch (_: Exception) {}
            return Trip(f, out)
        }

        private fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
            val R = 6371000.0
            val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
            val a = sin(dLa / 2) * sin(dLa / 2) +
                cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2) * sin(dLo / 2)
            return R * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
