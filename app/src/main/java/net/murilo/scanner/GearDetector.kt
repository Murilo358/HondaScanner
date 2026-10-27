package net.murilo.scanner

import android.content.Context
import kotlin.math.abs

/**
 * Deteccao de marcha por razao RPM/velocidade, com RAZOES FIXAS (SEM auto-aprendizado).
 * Em cada marcha, RPM/velocidade (km/h) e ~constante. Classifica a razao atual na marcha mais
 * proxima da tabela. N quando parado; segura a ultima durante troca/embreagem.
 *
 * Os centros abaixo sao do Civic LX 2000 5MT (cx S40: 3.25/1.77/1.19/0.91/0.70, final 4.27, pneu ~1.89m)
 * => rpm/kmh ~ [122, 67, 45, 34, 26]. AJUSTE AQUI conforme os testes:
 *  - se TODAS as marchas vierem erradas no mesmo sentido, mude o SCALE (↑ se vier baixa, ↓ se vier alta).
 *  - se uma marcha especifica estiver fora, mexa so no valor dela em CENTERS.
 */
object GearDetector {
    private const val SCALE = 1.0f
    private val CENTERS = floatArrayOf(122f, 67f, 45f, 34f, 26f)   // 1a..5a (razao rpm/kmh)

    private const val MIN_SPEED = 6f      // km/h minimo p/ reconhecer marcha
    private const val MIN_RPM = 1000f     // abaixo = lenta/embreagem -> segura

    private var lastGear = 0

    fun init(ctx: Context) { /* sem nada a carregar (sem aprendizado) */ }

    @Synchronized fun reset() { lastGear = 0 }

    @Synchronized fun update(rpm: Float, speed: Float, learn: Boolean = false): Int {
        if (speed < 3f) { lastGear = 0; return 0 }               // parado -> N
        if (speed < MIN_SPEED || rpm < MIN_RPM) return lastGear  // muito devagar/lenta: segura a ultima
        val ratio = rpm / speed
        var best = 0; var bestErr = Float.MAX_VALUE
        for (i in CENTERS.indices) {
            val err = abs(CENTERS[i] * SCALE - ratio)
            if (err < bestErr) { bestErr = err; best = i }        // marcha mais proxima (sempre escolhe uma)
        }
        lastGear = best + 1
        return lastGear
    }
}
