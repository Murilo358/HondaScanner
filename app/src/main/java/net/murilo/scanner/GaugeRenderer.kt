package net.murilo.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Gauge estilo NFS Underground 2 ("Grifo"), a partir das pecas em app_gauge_parts.
 * Arco + ponteiro = RPM (tacometro); numero grande central = velocidade (km/h); "N" = marcha.
 * A arte do grifo vem do VectorDrawable res/drawable/gauge_griffin.xml.
 *
 * Canvas master 512x512, centro (256,256). Angulos em graus a partir das 12h, horario positivo.
 * Desenhado centralizado (quadrado) tanto no celular quanto no Android Auto.
 * Mudou algo visual? E so aqui (geometria) e no gauge_griffin.xml (arte).
 */
class GaugeRenderer(private val context: Context, private val numFont: Typeface? = null) {

    // RPM de fundo de escala. Ajuste conforme o corte do seu motor (o arco vai 0..RPM_MAX).
    private val RPM_MAX = 9000f

    // --- geometria do master (do README / gauge_full.svg) ---
    private val SWEEP_START = -135f   // inicio do arco (graus da 12h)
    private val SWEEP = 185f          // varredura total (-135 -> +50)
    private val R_ARC = 223f          // raio do arco de RPM
    private val W_ARC = 28f
    private val R_RED = 193f          // zona vermelha
    private val W_RED = 20f
    private val RED_FROM = 31f; private val RED_TO = 55f
    private val R_DOTS = 182f; private val DOT = 10f

    // cores (README)
    private val CYAN = Color.parseColor("#c9f2f4")
    private val ARC_FILL = Color.parseColor("#a7d8e6")
    private val ARC_TRACK = Color.parseColor("#4fa8c4")
    private val RED = Color.parseColor("#d44e63")
    private val RING_DARK = Color.parseColor("#11181d")
    private val RING_EDGE = Color.parseColor("#2a343c")
    private val BG = Color.parseColor("#0a0f13")

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = numFont ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = CYAN
    }
    private val pointerPath = Path()

    // layout por frame
    private var sc = 1f; private var ox = 0f; private var oy = 0f
    private var cx = 0f; private var cy = 0f

    // grifo rasterizado (cache por tamanho)
    private var griffinBmp: Bitmap? = null
    private var griffinSide = 0

    private fun mx(x: Float) = ox + x * sc
    private fun my(y: Float) = oy + y * sc
    private fun oval(r: Float) = RectF(cx - r * sc, cy - r * sc, cx + r * sc, cy + r * sc)

    fun draw(c: Canvas, w: Int, h: Int, d: Dash) {
        c.drawColor(Color.BLACK)
        val side = min(w, h)
        if (side <= 0) return
        sc = side / 512f
        ox = (w - side) / 2f
        oy = (h - side) / 2f
        cx = mx(256f); cy = my(256f)

        drawBezel(c)
        drawTrack(c)
        drawProgress(c, d.rpm)
        drawRedline(c)
        drawDots(c)
        drawGriffin(c, side)
        drawPointer(c, d.rpm)
        drawReadout(c, d)
    }

    private fun drawBezel(c: Canvas) {
        p.style = Paint.Style.STROKE
        p.color = RING_DARK; p.strokeWidth = 34f * sc; c.drawCircle(cx, cy, 223f * sc, p)
        p.color = RING_EDGE; p.strokeWidth = 2f * sc; c.drawCircle(cx, cy, 240f * sc, p)
        p.strokeWidth = 1.5f * sc; c.drawCircle(cx, cy, 206f * sc, p)
    }

    private fun drawTrack(c: Canvas) {
        p.style = Paint.Style.STROKE; p.color = ARC_TRACK
        p.strokeWidth = W_ARC * sc; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(oval(R_ARC), SWEEP_START - 90f, SWEEP, false, p)
    }

    private fun drawProgress(c: Canvas, rpm: Float) {
        val frac = (rpm / RPM_MAX).coerceIn(0f, 1f)
        if (frac <= 0f) return
        p.style = Paint.Style.STROKE; p.color = ARC_FILL
        p.strokeWidth = W_ARC * sc; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(oval(R_ARC), SWEEP_START - 90f, SWEEP * frac, false, p)
    }

    private fun drawRedline(c: Canvas) {
        p.style = Paint.Style.STROKE; p.color = RED
        p.strokeWidth = W_RED * sc; p.strokeCap = Paint.Cap.BUTT
        c.drawArc(oval(R_RED), RED_FROM - 90f, RED_TO - RED_FROM, false, p)
    }

    private fun drawDots(c: Canvas) {
        p.style = Paint.Style.FILL; p.color = CYAN
        var g = -170f
        while (g <= 40f + 0.1f) {
            val a = Math.toRadians(g.toDouble())
            val x = cx + (R_DOTS * sc * sin(a)).toFloat()
            val y = cy - (R_DOTS * sc * cos(a)).toFloat()
            c.drawCircle(x, y, DOT * sc, p)
            g += 30f
        }
    }

    private fun drawGriffin(c: Canvas, side: Int) {
        val bmp = griffin(side) ?: return
        c.drawBitmap(bmp, ox, oy, null)
    }

    private fun griffin(side: Int): Bitmap? {
        if (griffinBmp != null && griffinSide == side) return griffinBmp
        val dr = AppCompatResources.getDrawable(context, R.drawable.gauge_griffin) ?: return null
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val cc = Canvas(bmp)
        dr.setBounds(0, 0, side, side)
        dr.draw(cc)
        griffinBmp?.recycle()
        griffinBmp = bmp; griffinSide = side
        return bmp
    }

    private fun drawPointer(c: Canvas, rpm: Float) {
        val frac = (rpm / RPM_MAX).coerceIn(0f, 1f)
        val g = SWEEP_START + SWEEP * frac
        pointerPath.reset()
        pointerPath.moveTo(mx(256f), my(150f))
        pointerPath.lineTo(mx(241.6f), my(106.7f))
        pointerPath.lineTo(mx(270.4f), my(106.7f))
        pointerPath.close()
        p.style = Paint.Style.FILL; p.color = CYAN
        c.save()
        c.rotate(g, cx, cy)
        c.drawPath(pointerPath, p)
        c.restore()
    }

    private fun drawReadout(c: Canvas, d: Dash) {
        // marcha (N / numero / -)
        val gear = if (d.gear > 0) d.gear.toString() else if (d.gear == 0) "N" else "-"
        text.textSize = 44f * sc; text.letterSpacing = 0f
        c.drawText(gear, mx(400f), my(234f), text)
        // velocidade (km/h)
        text.textSize = 52f * sc; text.letterSpacing = 0.04f
        c.drawText(d.speed.toInt().toString(), mx(349f), my(311f), text)
        // unidade
        text.textSize = 26f * sc; text.letterSpacing = 0.12f
        c.drawText("KM/H", mx(332f), my(371f), text)
        text.letterSpacing = 0f
    }
}
