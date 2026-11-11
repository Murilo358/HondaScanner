package net.murilo.scanner

import android.content.Context
import android.graphics.Color

/**
 * Cores customizaveis do painel (fundo, anel, ponteiro, zona vermelha, texto, grifo).
 * Lidas ao vivo pelo [ClusterRenderer] (celular + Android Auto); salvas em SharedPreferences.
 * O grifo e um TINT por multiplicacao: branco (padrao) = arte cinza original; outra cor tinge
 * mantendo o sombreado.
 */
object Theme {
    private const val PREF = "theme_prefs"

    // padrão preto + vermelho (tema do app). Tons distintos de propósito: anel aço (ticks saem
    // dele por lighten), preenchimento vermelho translúcido, ponteiro branco (não some sobre o
    // vermelho do preenchimento/zona), shift laranja.
    const val DEF_BG = 0xFF0B0A0A.toInt()
    const val DEF_RING = 0xFF6E7278.toInt()
    const val DEF_PROGRESS = 0xFFE23C3C.toInt()  // preenchimento do anel que segue o ponteiro
    const val DEF_NEEDLE = 0xFFF2EDEB.toInt()
    const val DEF_REDLINE = 0xFFE23C3C.toInt()
    const val DEF_TEXT = 0xFFF2EDEB.toInt()
    const val DEF_GRIFFIN = 0xFFE23C3C.toInt()   // tinge o grifo de vermelho (igual ao ícone)
    const val DEF_SHIFT_COLOR = 0xFFFF8C1A.toInt()
    const val DEF_SHIFT_RPM = 6500
    const val DEF_MENU_BG = 0xFF0A0A0A.toInt()      // menu: fundo (padrao preto)
    const val DEF_MENU_ACCENT = 0xFFE23C3C.toInt()  // menu: destaque (padrao vermelho)

    @Volatile var bg = DEF_BG
    @Volatile var ring = DEF_RING
    @Volatile var progress = DEF_PROGRESS
    @Volatile var needle = DEF_NEEDLE
    @Volatile var redline = DEF_REDLINE
    @Volatile var text = DEF_TEXT
    @Volatile var griffin = DEF_GRIFFIN
    @Volatile var shiftColor = DEF_SHIFT_COLOR
    @Volatile var shiftRpm = DEF_SHIFT_RPM
    @Volatile var shiftEnabled = true
    @Volatile var menuBg = DEF_MENU_BG
    @Volatile var menuAccent = DEF_MENU_ACCENT
    @Volatile var previewShift = false   // customizador: força o shift light aceso (não persiste)

    private var ctx: Context? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        val p = ctx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        bg = p.getInt("bg", DEF_BG); ring = p.getInt("ring", DEF_RING)
        progress = p.getInt("progress", DEF_PROGRESS)
        needle = p.getInt("needle", DEF_NEEDLE); redline = p.getInt("redline", DEF_REDLINE)
        text = p.getInt("text", DEF_TEXT); griffin = p.getInt("griffin", DEF_GRIFFIN)
        shiftColor = p.getInt("shiftColor", DEF_SHIFT_COLOR)
        shiftRpm = p.getInt("shiftRpm", DEF_SHIFT_RPM)
        shiftEnabled = p.getBoolean("shiftEnabled", true)
        menuBg = p.getInt("menuBg", DEF_MENU_BG); menuAccent = p.getInt("menuAccent", DEF_MENU_ACCENT)
    }

    fun get(key: String) = when (key) {
        "bg" -> bg; "ring" -> ring; "progress" -> progress; "needle" -> needle
        "redline" -> redline; "text" -> text; "griffin" -> griffin; "shiftColor" -> shiftColor
        "menuBg" -> menuBg; "menuAccent" -> menuAccent; else -> bg
    }

    fun set(key: String, color: Int) {
        when (key) {
            "bg" -> bg = color; "ring" -> ring = color; "progress" -> progress = color; "needle" -> needle = color
            "redline" -> redline = color; "text" -> text = color; "griffin" -> griffin = color
            "shiftColor" -> shiftColor = color
            "menuBg" -> menuBg = color; "menuAccent" -> menuAccent = color
        }
        try { ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putInt(key, color)?.apply() } catch (_: Exception) {}
    }

    fun setShift(enabled: Boolean, rpm: Int) {
        shiftEnabled = enabled; shiftRpm = rpm
        try {
            ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()
                ?.putBoolean("shiftEnabled", enabled)?.putInt("shiftRpm", rpm)?.apply()
        } catch (_: Exception) {}
    }

    fun reset() {
        bg = DEF_BG; ring = DEF_RING; progress = DEF_PROGRESS; needle = DEF_NEEDLE
        redline = DEF_REDLINE; text = DEF_TEXT; griffin = DEF_GRIFFIN
        shiftColor = DEF_SHIFT_COLOR; shiftRpm = DEF_SHIFT_RPM; shiftEnabled = true
        menuBg = DEF_MENU_BG; menuAccent = DEF_MENU_ACCENT
        try { ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.clear()?.apply() } catch (_: Exception) {}
    }

    // --- helpers de cor ---
    fun lighten(c: Int, f: Float) = Color.argb(255,
        Color.red(c) + ((255 - Color.red(c)) * f).toInt(),
        Color.green(c) + ((255 - Color.green(c)) * f).toInt(),
        Color.blue(c) + ((255 - Color.blue(c)) * f).toInt())

    fun darken(c: Int, f: Float) = Color.argb(255,
        (Color.red(c) * (1 - f)).toInt(), (Color.green(c) * (1 - f)).toInt(), (Color.blue(c) * (1 - f)).toInt())

    fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
