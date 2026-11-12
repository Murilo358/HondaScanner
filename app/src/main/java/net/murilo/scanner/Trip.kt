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
    val hasGps: Boolean get() = route.size >= 2

    /**
     * Pontos de GPS LIMPOS para mapa/imagem/distância. O cru vira "teia de aranha":
     * parado, o GPS deriva em volta do ponto; e fixes ruins teleportam o carro.
     * Regras (calibradas com 4 viagens reais do Civic em 2026-11):
     * - parado (<3 km/h) não cria ponto novo;
     * - fix só entra se couber no ODÔMETRO da ECU: soma de velocidade×tempo de TODAS as
     *   amostras desde o último ponto aceito (o OBD loga a 1 Hz mesmo com GPS ruim),
     *   ×1.5 de folga + 12 m de erro — andar mais que o carro andou é fisicamente impossível.
     *   Velocidade×dt só do par de pontos (versão antiga) inflava em vão longo de GPS;
     * - realocação (âncora errada): fix fora do orçamento precisa de 5 fixes seguintes
     *   consistentes (<100 m entre si) E carro a ≥15 km/h — multipath consistente acontece
     *   parado/se arrastando (era TODO salto que sobrava); túnel/garagem se resolve pelo
     *   odômetro ou realoca assim que o carro anda de verdade.
     */
    val route: List<TripSample> by lazy {
        val out = ArrayList<TripSample>()
        var last: TripSample? = null
        var pend: TripSample? = null   // candidato de realocação (salto a confirmar)
        var pendN = 0
        var odo = 0.0                  // metros rodados (ECU) desde o último ponto aceito
        var prevT = -1L; var prevSpd = 0f
        for (s in samples) {
            if (prevT >= 0) odo += maxOf(s.speed, prevSpd) / 3.6 * (s.t - prevT) / 1000.0
            prevT = s.t; prevSpd = s.speed
            if (s.lat == 0.0 && s.lon == 0.0) continue
            val l = last
            if (l == null) { out.add(s); last = s; odo = 0.0; continue }
            val d = haversine(l.lat, l.lon, s.lat, s.lon)
            val dt = (s.t - l.t) / 1000.0
            if (s.speed < 3f && d < 150) continue
            val budget = odo * 1.5 + 12.0
            if (dt <= 0 || d <= budget) { out.add(s); last = s; pend = null; pendN = 0; odo = 0.0; continue }
            val p = pend
            if (p != null && haversine(p.lat, p.lon, s.lat, s.lon) < 100.0) {
                pendN++
                if (pendN >= 5 && s.speed >= 15f) { out.add(s); last = s; pend = null; pendN = 0; odo = 0.0 }
                else pend = s
            } else { pend = s; pendN = 1 }
        }
        out
    }

    val distanceKm: Float by lazy {
        var d = 0.0; var prev: TripSample? = null
        for (s in route) { prev?.let { d += haversine(it.lat, it.lon, s.lat, s.lon) }; prev = s }
        (d / 1000.0).toFloat()
    }

    val fuelL: Float by lazy {   // integra a vazao (L/h) ao longo do tempo
        var l = 0.0; var prev: TripSample? = null
        for (s in samples) { prev?.let { l += s.fuelFlow * (s.t - it.t) / 3_600_000.0 }; prev = s }
        l.toFloat()
    }
    val avgKmL: Float get() = if (fuelL > 0.01f) distanceKm / fuelL else 0f

    companion object {
        // cache: reabrir a mesma sessão (lista → linha do tempo → mapa) não re-parseia o CSV
        private val cache = object : LinkedHashMap<String, Trip>(8, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Trip>) = size > 20
        }
        private fun key(f: File) = "${f.name}:${f.length()}"
        @Synchronized fun cached(f: File) = cache.containsKey(key(f))
        @Synchronized fun of(f: File): Trip = cache.getOrPut(key(f)) { load(f) }

        fun load(f: File): Trip {
            val out = ArrayList<TripSample>()
            try {
                f.bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { ln ->
                        var p = ln.split(",")
                        // Sessões antigas gravadas em pt-BR: o decimal saiu como vírgula e cada linha
                        // virou 23 campos (lat, lon, bateria, injeção, lambda, vazão e consumo partidos
                        // em dois). A posição é fixa, então dá pra remontar e não perder o histórico.
                        if (p.size == 23) {
                            fun j(a: Int) = p[a] + "." + p[a + 1]
                            p = listOf(p[0], j(1), j(3), p[5], p[6], p[7], p[8], p[9], p[10], p[11],
                                j(12), j(14), j(16), j(18), p[20], j(21))
                        }
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
