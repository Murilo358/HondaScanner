package net.murilo.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * CRUD local dos layouts PERSONALIZADOS (editados a mao). Cada layout tem nome + lista de widgets.
 * Salvo em SharedPreferences como JSON. Lido pelo [ClusterRenderer] (via [Layout]) no celular e no AA.
 */
object CustomStore {
    private const val PREF = "custom_layouts"
    private const val KEY = "data"

    class CLayout(var name: String, val widgets: MutableList<Widget> = mutableListOf())

    val layouts = mutableListOf<CLayout>()
    private var ctx: Context? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        load()
    }

    fun names(): List<String> = layouts.map { it.name }
    fun get(i: Int): CLayout? = layouts.getOrNull(i)

    /** Cria um layout novo e retorna o indice dele. */
    fun add(name: String): Int {
        layouts.add(CLayout(name))
        save(); return layouts.size - 1
    }

    fun rename(i: Int, name: String) { layouts.getOrNull(i)?.let { it.name = name; save() } }
    fun delete(i: Int) { if (i in layouts.indices) { layouts.removeAt(i); save() } }

    @Synchronized fun save() {
        try {
            val arr = JSONArray()
            for (l in layouts) {
                val o = JSONObject(); o.put("name", l.name)
                val wa = JSONArray()
                for (w in l.widgets) {
                    val wo = JSONObject()
                    wo.put("t", w.type); wo.put("x", w.x.toDouble()); wo.put("y", w.y.toDouble())
                    wo.put("w", w.w.toDouble()); wo.put("h", w.h.toDouble())
                    wa.put(wo)
                }
                o.put("w", wa); arr.put(o)
            }
            ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putString(KEY, arr.toString())?.apply()
        } catch (_: Exception) {}
    }

    private fun load() {
        try {
            val s = ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.getString(KEY, null) ?: return
            layouts.clear()
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val l = CLayout(o.optString("name", "Layout ${i + 1}"))
                val wa = o.optJSONArray("w") ?: JSONArray()
                for (j in 0 until wa.length()) {
                    val wo = wa.getJSONObject(j)
                    l.widgets.add(Widget(
                        wo.optString("t", "g_rpm"),
                        wo.optDouble("x", 0.1).toFloat(), wo.optDouble("y", 0.1).toFloat(),
                        wo.optDouble("w", 0.2).toFloat(), wo.optDouble("h", 0.4).toFloat()
                    ))
                }
                layouts.add(l)
            }
        } catch (_: Exception) {}
    }
}
