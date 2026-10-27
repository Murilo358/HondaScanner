package net.murilo.scanner

import android.content.Context

/** Fundo de tela: 12 texturas SVG (bg01..bg12) + "Nenhum". O renderer aplica um tint que segue a
 *  cor de fundo escolhida (Theme.bg). Selecao salva em SharedPreferences. */
object BgTex {
    private const val PREF = "bgtex_prefs"

    // indice 0 = Nenhum; 1..12 -> drawable bg01..bg12 (ordem dos arquivos 01..12)
    val labels = listOf(
        "Nenhum", "Gradiente obsidiana", "Metal escovado", "Carbono diagonal", "Carbono tramado",
        "Satin grafite", "Halo central", "Fumaça grafite", "Micrograde técnica", "Hexágono stealth",
        "Granito matte", "Alumínio anodizado", "Carbono híbrido"
    )

    @Volatile var selected = 0
    private var ctx: Context? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        try { selected = ctx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("sel", 0) } catch (_: Exception) {}
    }

    fun set(i: Int) {
        selected = i
        try { ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putInt("sel", i)?.apply() } catch (_: Exception) {}
    }

    fun resIdFor(context: Context, i: Int): Int {
        if (i <= 0) return 0
        return context.resources.getIdentifier(String.format("bg%02d", i), "drawable", context.packageName)
    }
}
