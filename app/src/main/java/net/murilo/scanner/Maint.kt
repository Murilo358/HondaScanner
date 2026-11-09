package net.murilo.scanner

import android.content.Context

/** Lembretes de manutenção por km, usando o hodômetro do app ([Stats.totalKm]).
 *  Itens de fábrica + serviços cadastrados pelo usuário (persistidos em prefs). */
object Maint {
    class Item(val key: String, var name: String, var intervalKm: Float, var lastKm: Float,
               val custom: Boolean = false)

    private fun builtin() = listOf(
        Item("oil", "Troca de óleo", 5000f, 0f),
        Item("oilfilter", "Filtro de óleo", 10000f, 0f),
        Item("airfilter", "Filtro de ar", 10000f, 0f),
        Item("plugs", "Velas", 20000f, 0f),
        Item("belt", "Correia dentada", 60000f, 0f),
        Item("coolant", "Fluido de arrefecimento", 40000f, 0f)
    )
    val items = ArrayList<Item>()
    private var ctx: Context? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        items.clear(); items.addAll(builtin())
        try {
            val p = ctx!!.getSharedPreferences("maint_prefs", Context.MODE_PRIVATE)
            items.forEach { it.intervalKm = p.getFloat("${it.key}_int", it.intervalKm); it.lastKm = p.getFloat("${it.key}_last", 0f) }
            for (k in p.getStringSet("custom_keys", emptySet()) ?: emptySet()) {
                items.add(Item(k, p.getString("${k}_name", "Serviço") ?: "Serviço",
                    p.getFloat("${k}_int", 10000f), p.getFloat("${k}_last", 0f), custom = true))
            }
        } catch (_: Exception) {}
    }

    private fun prefs() = ctx?.getSharedPreferences("maint_prefs", Context.MODE_PRIVATE)

    private fun save(i: Item) {
        try {
            prefs()?.edit()?.apply {
                putFloat("${i.key}_int", i.intervalKm); putFloat("${i.key}_last", i.lastKm)
                if (i.custom) putString("${i.key}_name", i.name)
            }?.apply()
        } catch (_: Exception) {}
    }

    private fun saveCustomKeys() {
        try { prefs()?.edit()?.putStringSet("custom_keys", items.filter { it.custom }.map { it.key }.toSet())?.apply() } catch (_: Exception) {}
    }

    /** Cadastra um serviço do usuário. Começa contado a partir do hodômetro atual. */
    fun addCustom(name: String, intervalKm: Float): Item {
        val i = Item("c${System.currentTimeMillis()}", name, intervalKm, Stats.totalKm, custom = true)
        items.add(i); save(i); saveCustomKeys()
        return i
    }

    /** Remove um serviço cadastrado (os de fábrica ficam). */
    fun removeCustom(i: Item) {
        if (!i.custom) return
        items.remove(i)
        try { prefs()?.edit()?.remove("${i.key}_int")?.remove("${i.key}_last")?.remove("${i.key}_name")?.apply() } catch (_: Exception) {}
        saveCustomKeys()
    }

    /** km restantes até o serviço (negativo = vencido). */
    fun remainingKm(i: Item) = i.intervalKm - (Stats.totalKm - i.lastKm)
    fun markDone(i: Item) { i.lastKm = Stats.totalKm; save(i) }
    fun setInterval(i: Item, km: Float) { i.intervalKm = km; save(i) }
    fun anyOverdue() = items.any { remainingKm(it) < 0f }
}
