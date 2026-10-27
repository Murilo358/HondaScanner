package net.murilo.scanner

import android.content.Context

/** Lembretes de manutenção por km, usando o hodômetro do app ([Stats.totalKm]). */
object Maint {
    class Item(val key: String, val name: String, var intervalKm: Float, var lastKm: Float)

    val items = listOf(
        Item("oil", "Troca de óleo", 5000f, 0f),
        Item("oilfilter", "Filtro de óleo", 10000f, 0f),
        Item("airfilter", "Filtro de ar", 10000f, 0f),
        Item("plugs", "Velas", 20000f, 0f),
        Item("belt", "Correia dentada", 60000f, 0f),
        Item("coolant", "Fluido de arrefecimento", 40000f, 0f)
    )
    private var ctx: Context? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        try {
            val p = ctx!!.getSharedPreferences("maint_prefs", Context.MODE_PRIVATE)
            items.forEach { it.intervalKm = p.getFloat("${it.key}_int", it.intervalKm); it.lastKm = p.getFloat("${it.key}_last", 0f) }
        } catch (_: Exception) {}
    }

    private fun save(i: Item) {
        try { ctx?.getSharedPreferences("maint_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putFloat("${i.key}_int", i.intervalKm)?.putFloat("${i.key}_last", i.lastKm)?.apply() } catch (_: Exception) {}
    }

    /** km restantes até o serviço (negativo = vencido). */
    fun remainingKm(i: Item) = i.intervalKm - (Stats.totalKm - i.lastKm)
    fun markDone(i: Item) { i.lastKm = Stats.totalKm; save(i) }
    fun setInterval(i: Item, km: Float) { i.intervalKm = km; save(i) }
    fun anyOverdue() = items.any { remainingKm(it) < 0f }
}
