package net.murilo.scanner

import android.content.Context

/**
 * Combustivel SEM sensor de nivel na ECU (OBD-1 Honda nao manda nivel):
 * calcula a VAZAO pela injecao e INTEGRA o consumo; o nivel = tanque_inicial - consumido.
 *
 * vazao(L/h) = cilindros × (rpm/2 injecoes/min) × injDur(ms) × vazao_bico(cc/min) / 60000 × 60/1000 × corr
 * economia(km/L) = velocidade / vazao ; autonomia = nivel × media_movel(economia).
 *
 * Calibracao: rode 1 tanque e ajuste [corr] pra bater com o abastecimento real. Depois so toca
 * "tanque cheio" quando abastecer (nao precisa registrar cada abastecimento).
 */
object FuelCalc {
    private const val PREF = "fuel_prefs"

    @Volatile var tankL = 45f       // capacidade do tanque (L)
    @Volatile var injCc = 240f      // vazao do bico (cc/min) — Civic ~190-240
    @Volatile var cylinders = 4
    @Volatile var corr = 1.0f       // fator de calibracao

    @Volatile var everFilled = false       // ja marcou "tanque cheio" alguma vez? antes disso o nivel e chute
        private set
    @Volatile private var consumedL = 0f   // litros desde o ultimo "tanque cheio"
    private var avgEcon = 0f               // media movel km/L (p/ autonomia)
    private var lastNs = 0L
    private var ctx: Context? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        try {
            val p = ctx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            tankL = p.getFloat("tank", 45f); injCc = p.getFloat("injcc", 240f)
            cylinders = p.getInt("cyl", 4); corr = p.getFloat("corr", 1f)
            consumedL = p.getFloat("consumed", 0f); avgEcon = p.getFloat("avgecon", 0f)
            everFilled = p.getBoolean("filled", false)
        } catch (_: Exception) {}
    }

    private fun save() {
        try {
            ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()
                ?.putFloat("tank", tankL)?.putFloat("injcc", injCc)?.putInt("cyl", cylinders)
                ?.putFloat("corr", corr)?.putFloat("consumed", consumedL)?.putFloat("avgecon", avgEcon)
                ?.putBoolean("filled", everFilled)?.apply()
        } catch (_: Exception) {}
    }

    fun tick(d: Dash) {
        val now = System.nanoTime()
        if (lastNs == 0L) { lastNs = now; return }
        val dtH = (now - lastNs) / 3_600_000_000_000.0   // ns -> horas
        lastNs = now
        if (dtH <= 0.0 || dtH > 0.01) return             // ignora saltos (>36s) / pausas

        val ccPerMin = cylinders * (d.rpm / 2f) * d.injDur * (injCc / 60000f)  // cc/min
        val lh = (ccPerMin * 60f / 1000f) * corr                              // L/h
        d.fuelFlowLh = lh
        d.economyKmL = if (lh > 0.05f && d.speed > 1f) (d.speed / lh) else 0f

        val usedL = (lh * dtH).toFloat()
        if (usedL in 0f..0.05f) { consumedL += usedL; d.tripFuelL += usedL }

        d.fuelLevelL = (tankL - consumedL).coerceIn(0f, tankL)
        d.fuelPct = (d.fuelLevelL / tankL * 100f).coerceIn(0f, 100f)
        if (d.economyKmL in 1f..40f)
            avgEcon = if (avgEcon <= 0f) d.economyKmL else avgEcon * 0.995f + d.economyKmL * 0.005f
        d.autonomyKm = d.fuelLevelL * (if (avgEcon > 0f) avgEcon else 10f)

        if ((now / 1_000_000_000L) % 10L == 0L) save()
    }

    /** "Enchi o tanque" — zera o consumido (nivel = cheio). */
    fun fillFull() { consumedL = 0f; everFilled = true; save() }

    fun setConfig(tank: Float, cc: Float, cyl: Int, correction: Float) {
        tankL = tank; injCc = cc; cylinders = cyl; corr = correction; save()
    }

    fun resetTrip(d: Dash) { d.tripFuelL = 0f }
}
