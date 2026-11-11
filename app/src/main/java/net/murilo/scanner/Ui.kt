package net.murilo.scanner

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Paleta e blocos do design final das telas utilitárias (preto verdadeiro, cartões #111,
 *  uma cor de dado, ação primária = pílula branca). Usado por Trips/Dtc/Perf/Report/Maint/Settings. */
object Ui {
    const val BG = 0xFF000000.toInt()
    const val CARD = 0xFF111111.toInt()
    const val RAISED = 0xFF2A2A2A.toInt()
    const val RULE = 0xFF1C1C1C.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFFB4B4B4.toInt()
    const val FAINT = 0xFF949494.toInt()
    const val ACCENT = 0xFFE23C3C.toInt()   // vermelho: única cor de dado (tema do app)
    const val OK = 0xFF2DC06E.toInt()       // verde: estado bom (não usar o accent, que agora é vermelho)
    const val NEG = 0xFFE5484D.toInt()
    const val WARN = 0xFFE5A93B.toInt()
    const val PILL_WARN_BG = 0xFF2B2414.toInt()

    fun dp(c: Context, v: Int) = Math.round(v * c.resources.displayMetrics.density)

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply { cornerRadius = radiusPx; setColor(color) }

    /** Cartão escuro (#111, raio 20dp). */
    fun card(c: Context, vertical: Boolean = true) = LinearLayout(c).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (!vertical) gravity = Gravity.CENTER_VERTICAL
        background = rounded(CARD, dp(c, 20).toFloat())
        setPadding(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14))
    }

    fun text(c: Context, s: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(c).apply {
            text = s; textSize = sizeSp; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** Pílula de ação: primária (branca, texto preto) ou fantasma (#2a2a2a, texto branco). */
    fun pill(c: Context, label: String, primary: Boolean, onClick: () -> Unit) = TextView(c).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (primary) Color.BLACK else TEXT)
        if (primary) setTypeface(typeface, Typeface.BOLD)
        background = rounded(if (primary) Color.WHITE else RAISED, dp(c, 999).toFloat())
        minHeight = dp(c, 40)
        setPadding(dp(c, 20), dp(c, 9), dp(c, 20), dp(c, 9))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Selo de estado (fundo tingido pálido + texto da mesma cor). */
    fun statusPill(c: Context, label: String, bg: Int, ink: Int) = TextView(c).apply {
        text = label; textSize = 11f; setTextColor(ink)
        background = rounded(bg, dp(c, 999).toFloat())
        setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3))
    }

    /** Cabeçalho da tela: ‹ voltar + título + texto à direita (opcional). */
    fun header(c: Context, title: String, right: CharSequence?, onBack: () -> Unit): LinearLayout {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(c, "‹", 26f, MUTED).apply {
            minWidth = dp(c, 38); setPadding(0, 0, dp(c, 8), dp(c, 2))
            setOnClickListener { onBack() }
        })
        row.addView(text(c, title, 20f, TEXT, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (right != null) row.addView(text(c, right, 12f, MUTED))
        return row
    }

    /** Tela de espera simples, usada enquanto o CSV da sessão é lido fora da UI. */
    fun loading(c: Context, msg: String) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(BG)
        addView(android.widget.ProgressBar(c))
        addView(text(c, msg, 14f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(c, 14), 0, 0) })
    }

    /** Barra fina de proporção (6dp): trilho #2a2a2a + preenchimento na fração dada. */
    fun bar(c: Context, frac: Float, fillColor: Int): FrameLayout {
        val hpx = dp(c, 6)
        val f = FrameLayout(c)
        f.addView(android.view.View(c).apply { background = rounded(RAISED, hpx / 2f) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hpx))
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val fr = frac.coerceIn(0f, 1f)
        row.addView(android.view.View(c).apply { background = rounded(fillColor, hpx / 2f) },
            LinearLayout.LayoutParams(0, hpx, fr))
        row.addView(android.view.View(c), LinearLayout.LayoutParams(0, hpx, 1f - fr))
        f.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hpx))
        return f
    }
}
