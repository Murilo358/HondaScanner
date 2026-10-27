package net.murilo.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat

/** View do celular: desenha o painel com o DashboardRenderer compartilhado. */
class DashboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    val dash = Dash()
    private val renderer: ClusterRenderer

    init {
        val font: Typeface? = try { ResourcesCompat.getFont(context, R.font.rajdhani_bold) } catch (_: Exception) { null }
        renderer = ClusterRenderer(context, font)
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width, height, dash)
    }
}
