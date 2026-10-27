package net.murilo.scanner

import android.content.Context
import kotlin.math.max

/**
 * Desempenho: cronometra 0-100 e 0-60 km/h (detecta a largada sozinho) e estima potência (dyno
 * virtual) pela aceleração: a = dv/dt; F = massa × a; P = F × v; HP = P / 745.7. Aproximado
 * (ignora arrasto/rolamento), mas útil pra comparar.
 */
object PerfMeter {
    private const val PREF = "perf_prefs"
    @Volatile var massKg = 1150f        // massa do carro + ocupante (config)
    @Volatile var best0to100 = 0f       // melhor tempo (s) — 0 = sem registro
    @Volatile var best0to60 = 0f
    @Volatile var peakHp = 0f           // pico de potência estimado

    @Volatile var liveHp = 0f           // potência instantânea (dyno)
    @Volatile var runMs = 0L            // tempo do run atual (0 = parado)

    private var armed = true
    private var running = false
    private var t0 = 0L
    private var t60 = 0f
    private var lastV = 0f
    private var lastT = 0L
    private var ctx: Context? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        try {
            val p = ctx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            massKg = p.getFloat("mass", 1150f); best0to100 = p.getFloat("b100", 0f)
            best0to60 = p.getFloat("b60", 0f); peakHp = p.getFloat("peakhp", 0f)
        } catch (_: Exception) {}
    }

    private fun save() {
        try { ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()
            ?.putFloat("mass", massKg)?.putFloat("b100", best0to100)?.putFloat("b60", best0to60)
            ?.putFloat("peakhp", peakHp)?.apply() } catch (_: Exception) {}
    }

    fun tick(d: Dash) {
        val now = System.currentTimeMillis()
        val v = d.speed
        if (lastT != 0L) {
            val dtS = (now - lastT) / 1000f
            if (dtS in 0.01f..1f) {
                val a = ((v - lastV) / 3.6f) / dtS           // m/s²
                if (a > 0.3f && v > 2f) {
                    val hp = (massKg * a * (v / 3.6f)) / 745.7f
                    liveHp = liveHp * 0.7f + hp * 0.3f
                    if (liveHp > peakHp) { peakHp = liveHp; save() }
                } else liveHp *= 0.7f
            }
        }
        // cronômetro 0-100 / 0-60 com detecção de largada
        if (v < 1.5f) { armed = true; running = false; runMs = 0L }
        else if (armed && v >= 2f) { armed = false; running = true; t0 = now; t60 = 0f }
        if (running) {
            runMs = now - t0
            if (t60 == 0f && v >= 60f) t60 = runMs / 1000f
            if (v >= 100f) {
                val t = runMs / 1000f
                if (best0to100 == 0f || t < best0to100) best0to100 = t
                if (t60 > 0f && (best0to60 == 0f || t60 < best0to60)) best0to60 = t60
                running = false; save()
            }
        }
        lastV = v; lastT = now
    }

    fun resetBest() { best0to100 = 0f; best0to60 = 0f; peakHp = 0f; liveHp = 0f; save() }
    fun setMass(m: Float) { massKg = max(500f, m); save() }
}
