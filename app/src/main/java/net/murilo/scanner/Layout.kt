package net.murilo.scanner

import android.content.Context

/**
 * Layout do painel escolhido pelo usuario. Indices 0..6 = embutidos; 7+ = layouts PERSONALIZADOS
 * (editados a mao, guardados no [CustomStore]). Lido ao vivo pelo [ClusterRenderer] (celular + AA).
 *
 * 0 Atual · 1 Adaptativo · 2 Cartoes · 3 Digital · 4 Parede · 5 Underground · 6 Tres Grandes
 */
object Layout {
    private const val PREF = "layout_prefs"
    private const val KEY = "sel"

    const val BUILTIN = 7
    private val builtin = listOf(
        "Atual (padrão)",
        "Adaptativo (3, ou 2+temp no split)",
        "Cartões quadrados",
        "Digital minimalista",
        "Mostrador + parede de dados",
        "Underground",
        "3 Grandes (preenche a tela)"
    )

    /** Embutidos + nomes dos personalizados. */
    val labels: List<String> get() = builtin + CustomStore.names()

    @Volatile var selected = 0
        private set

    private var ctx: Context? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        CustomStore.init(c)
        selected = try { ctx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY, 0) } catch (_: Exception) { 0 }
    }

    fun set(i: Int) {
        val n = labels.size
        selected = i.coerceIn(0, (n - 1).coerceAtLeast(0))
        try { ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putInt(KEY, selected)?.apply() } catch (_: Exception) {}
    }

    fun isCustom(sel: Int) = sel >= BUILTIN
    fun customIndex(sel: Int) = sel - BUILTIN
}
