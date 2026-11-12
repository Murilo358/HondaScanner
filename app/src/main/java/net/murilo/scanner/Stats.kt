package net.murilo.scanner

import android.content.Context

/** Estatísticas vitalícias + hodômetro próprio do app (integra velocidade/tempo). */
object Stats {
    private const val PREF = "stats_prefs"
    @Volatile var totalKm = 0f
    @Volatile var engineHours = 0f
    @Volatile var maxSpeedEver = 0f
    @Volatile var maxRpmEver = 0f
    @Volatile var vtecCount = 0
    @Volatile var redlineHits = 0

    private var lastT = 0L
    private var prevVtec = false
    private var prevRedline = false
    private var ctx: Context? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        try {
            val p = ctx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            totalKm = p.getFloat("km", 0f); engineHours = p.getFloat("hours", 0f)
            maxSpeedEver = p.getFloat("maxspd", 0f); maxRpmEver = p.getFloat("maxrpm", 0f)
            vtecCount = p.getInt("vtec", 0); redlineHits = p.getInt("redline", 0)
        } catch (_: Exception) {}
    }

    private fun save() {
        try { ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()
            ?.putFloat("km", totalKm)?.putFloat("hours", engineHours)?.putFloat("maxspd", maxSpeedEver)
            ?.putFloat("maxrpm", maxRpmEver)?.putInt("vtec", vtecCount)?.putInt("redline", redlineHits)?.apply() } catch (_: Exception) {}
    }

    fun tick(d: Dash) {
        // simulação não soma km/horas nem bate máximos vitalícios
        if (!d.connected) { lastT = 0L; prevVtec = false; prevRedline = false; return }
        val now = System.currentTimeMillis()
        if (lastT != 0L) {
            val dtH = (now - lastT) / 3_600_000.0
            if (dtH in 0.0..0.001) {
                totalKm += (d.speed * dtH).toFloat()
                if (d.rpm > 300f) engineHours += dtH.toFloat()
            }
        }
        if (d.speed > maxSpeedEver) maxSpeedEver = d.speed
        if (d.rpm > maxRpmEver) maxRpmEver = d.rpm
        if (d.vtec && !prevVtec) vtecCount++; prevVtec = d.vtec
        val rl = d.rpm >= Feat.REDLINE_RPM
        if (rl && !prevRedline) redlineHits++; prevRedline = rl
        lastT = now
        if ((now / 1000L) % 15L == 0L) save()
    }

    fun resetAll() { totalKm = 0f; engineHours = 0f; maxSpeedEver = 0f; maxRpmEver = 0f; vtecCount = 0; redlineHits = 0; save() }
}
