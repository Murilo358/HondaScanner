package net.murilo.scanner

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Desenha o painel clássico num Canvas, dado o tamanho e o [Dash].
 * Geometria em "unidades GL" (centro na origem, y para cima) convertida para pixels de tela.
 * Usado tanto no celular quanto no Android Auto.
 *
 * Mudou algo visual? E so aqui.
 */
class DashboardRenderer(private val numFont: Typeface? = null) {

    // ---- geometria (unidades GL, extraidas do gl.json do app) ----
    private val RPM_MAX = 10000
    private val REV_LIMIT = 8400
    private val TACHO = floatArrayOf(144.5f, 196.1f)
    private val INNER_RING = floatArrayOf(136.0f, 144.5f)
    private val OUTER = 207.5f
    private val REV_U = floatArrayOf(175.0f, 196.4f)
    private val X1000 = floatArrayOf(176.0f, 194.0f)
    private val X500 = floatArrayOf(176.0f, 192.0f)
    private val X100 = floatArrayOf(176.0f, 181.0f)
    private val NUM_R = 159f
    private val GAUGE_ASPECT = 1.2777778f
    private val MIN_PANE = 0.10f
    private val ACCENT = Color.rgb(0xE3, 0x26, 0x2f)
    private val RED = Color.rgb(0xC3, 0, 0)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    // estado de layout por frame
    private var sc = 1f; private var cx = 0f; private var cy = 0f

    private fun ptX(a: Double, r: Float) = cx + (r * sc * cos(a)).toFloat()
    private fun ptY(a: Double, r: Float) = cy - (r * sc * sin(a)).toFloat()
    private fun rpmAngle(rpm: Float) = Math.toRadians(210.0 - rpm * 240.0 / RPM_MAX)

    fun draw(c: Canvas, w: Int, h: Int, d: Dash) {
        c.drawColor(Color.BLACK)
        val aspect = w.toFloat() / h
        val pane = max(((aspect - GAUGE_ASPECT) / 2f) / aspect, MIN_PANE)
        val paneW = pane * w
        val cw = w * (1 - 2 * pane)
        val axisX = max(cw / h, GAUGE_ASPECT) * 206f
        sc = cw / (2 * axisX)
        cx = w / 2f; cy = h / 2f

        drawPanels(c, w, h, paneW, d)
        drawDialFace(c)
        drawRevZone(c)
        drawTicks(c)
        drawNumbers(c)
        drawSideGauges(c, d)
        drawGearBox(c, d)
        drawNeedle(c, d.rpm)
        drawCenter(c, d)
        drawStatus(c, w, h, d)
    }

    private fun drawDialFace(c: Canvas) {
        // anel branco
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        c.drawCircle(cx, cy, TACHO[1] * sc, p)
        // bisel: do centro (preto) ate o anel interno (branco)
        val r1 = INNER_RING[1] * sc
        p.shader = android.graphics.RadialGradient(cx, cy, r1, intArrayOf(Color.BLACK, Color.BLACK, Color.WHITE),
            floatArrayOf(0f, INNER_RING[0] / INNER_RING[1], 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r1, p)
        p.shader = null
        // centro preto (area dos textos)
        p.color = Color.BLACK
        c.drawCircle(cx, cy, INNER_RING[0] * sc, p)
    }

    private fun drawRevZone(c: Canvas) {
        val a0 = rpmAngle(REV_LIMIT.toFloat()); val a1 = rpmAngle(RPM_MAX.toFloat())
        p.color = RED; p.style = Paint.Style.STROKE
        p.strokeWidth = (REV_U[1] - REV_U[0]) * sc
        val r = (REV_U[0] + REV_U[1]) / 2 * sc
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        // angulos GL -> graus de tela (y invertido): screenDeg = -GLdeg
        val s0 = -Math.toDegrees(a1).toFloat()
        val sweep = (-Math.toDegrees(a0) - s0).toFloat()
        c.drawArc(rect, s0, sweep, false, p)
    }

    private fun tickPaint(w: Float, col: Int) { p.style = Paint.Style.STROKE; p.strokeWidth = w * sc; p.color = col }

    private fun drawTicks(c: Canvas) {
        tickPaint(1.2f, Color.argb(210, 90, 90, 90))
        var v = 0
        while (v <= RPM_MAX) { if (v % 500 != 0) line(c, v, X100) ; v += 100 }
        v = 0; while (v <= RPM_MAX) { if (v % 1000 != 0) line(c, v, X500); v += 500 }
        tickPaint(2.0f, Color.argb(230, 60, 60, 60))
        v = 0; while (v <= RPM_MAX) { line(c, v, X1000); v += 1000 }
    }

    private fun line(c: Canvas, rpm: Int, rads: FloatArray) {
        val a = rpmAngle(rpm.toFloat())
        c.drawLine(ptX(a, rads[0]), ptY(a, rads[0]), ptX(a, rads[1]), ptY(a, rads[1]), p)
    }

    private fun drawNumbers(c: Canvas) {
        text.typeface = numFont ?: Typeface.DEFAULT_BOLD
        text.color = Color.BLACK
        text.textSize = 34f * sc
        for (i in 0..RPM_MAX / 1000) {
            val a = rpmAngle(i * 1000f)
            val x = ptX(a, NUM_R); val y = ptY(a, NUM_R) - (text.ascent() + text.descent()) / 2
            if (i * 1000 >= REV_LIMIT) text.color = Color.WHITE
            c.drawText(i.toString(), x, y, text)
            text.color = Color.BLACK
        }
    }

    private fun drawNeedle(c: Canvas, rpm: Float) {
        val a = rpmAngle(rpm.coerceIn(0f, RPM_MAX.toFloat()))
        val tip = 182f; val baseR = 8f; val halfW = Math.PI / 2
        val path = Path()
        path.moveTo(ptX(a, tip), ptY(a, tip))
        path.lineTo(ptX(a + halfW, baseR), ptY(a + halfW, baseR))
        path.lineTo(ptX(a - halfW, baseR), ptY(a - halfW, baseR))
        path.close()
        p.style = Paint.Style.FILL; p.color = Color.RED
        c.drawPath(path, p)
        p.color = Color.rgb(0x40, 0, 0)
        c.drawCircle(cx, cy, 10f * sc, p)
    }

    private fun drawCenter(c: Canvas, d: Dash) {
        text.typeface = Typeface.DEFAULT_BOLD
        // VTEC
        text.color = if (d.vtec) Color.WHITE else Color.rgb(0x3e, 0x3e, 0x3e)
        text.textSize = 15.5f * sc
        c.drawText("VTEC", cx, cy - 128.3f * sc, text)
        // rpm
        text.color = Color.WHITE; text.textSize = 37f * sc
        c.drawText(d.rpm.toInt().toString(), cx, cy - 105.3f * sc + 13 * sc, text)
        text.textSize = 14.5f * sc; text.color = Color.LTGRAY
        c.drawText("rpm", cx, cy - 84.9f * sc + 5 * sc, text)
        // velocidade
        text.color = Color.WHITE; text.textSize = 82f * sc
        c.drawText(d.speed.toInt().toString(), cx, cy - 41.5f * sc + 30 * sc, text)
        text.textSize = 14.5f * sc; text.color = Color.LTGRAY
        c.drawText("km/h", cx, cy - 2f * sc + 5 * sc, text)
        // divisoria
        p.style = Paint.Style.FILL; p.color = Color.rgb(0xf2, 0xf2, 0xf2)
        c.drawRect(cx - 80 * sc, cy + 9.2f * sc, cx + 80 * sc, cy + 10.8f * sc, p)
    }

    /** Gauge curvo lateral simplificado: arco grosso com preenchimento. */
    private fun drawSideGauge(c: Canvas, leftSide: Boolean, upper: Boolean, frac: Float,
                              label: String, value: String) {
        val r = 252f
        val yb = floatArrayOf(48f, 179.6f)
        fun ang(y: Float): Double {
            val x = sqrt(r * r - y * y) * (if (leftSide) -1 else 1)
            val a = Math.atan2(y.toDouble(), x.toDouble())
            return if (upper) a else -a
        }
        val aLo = ang(yb[0]); val aHi = ang(yb[1])
        val rect = RectF(cx - r * sc, cy - r * sc, cx + r * sc, cy + r * sc)
        p.style = Paint.Style.STROKE; p.strokeWidth = 11f * sc
        // trilho
        p.color = Color.rgb(0x32, 0x32, 0x32)
        var s0 = -Math.toDegrees(aLo).toFloat(); var sweep = (-Math.toDegrees(aHi) - s0).toFloat()
        c.drawArc(rect, s0, sweep, false, p)
        // preenchimento branco proporcional
        p.color = Color.WHITE
        c.drawArc(rect, s0, sweep * frac.coerceIn(0f, 1f), false, p)
        // textos
        val amid = (aLo + aHi) / 2
        val tx = ptX(amid, if (leftSide) 212f else 215f); val ty = ptY(amid, 200f)
        text.typeface = Typeface.DEFAULT_BOLD; text.color = Color.WHITE; text.textSize = 23f * sc
        c.drawText(value, tx, ty, text)
        text.typeface = Typeface.DEFAULT; text.color = Color.LTGRAY; text.textSize = 11f * sc
        c.drawText(label, tx, ty + 15f * sc, text)
    }

    private fun drawSideGauges(c: Canvas, d: Dash) {
        drawSideGauge(c, true, true, (d.ect + 20) / 140f, "Temp. liq.", "%.0f°".format(d.ect))
        drawSideGauge(c, false, true, (d.iat + 20) / 140f, "Temp. ar", "%.0f°".format(d.iat))
        drawSideGauge(c, true, false, d.tps / 100f, "Acelerador", "%.0f%%".format(d.tps))
        drawSideGauge(c, false, false, d.map / 1200f, "MAP", "%.0f".format(d.map))
    }

    private fun drawGearBox(c: Canvas, d: Dash) {
        val x = cx - 236.9f * sc; val s = 20f * sc
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * sc; p.color = Color.GRAY
        c.drawRect(x - s, cy - s, x + s, cy + s, p)
        text.typeface = Typeface.DEFAULT_BOLD; text.color = Color.WHITE; text.textSize = 33f * sc
        val g = if (d.gear > 0) d.gear.toString() else if (d.gear == 0) "N" else "-"
        c.drawText(g, x, cy - (text.ascent() + text.descent()) / 2, text)
    }

    // ---- 6 paineis laterais ----
    private fun drawPanels(c: Canvas, w: Int, h: Int, paneW: Float, d: Dash) {
        val ph = h / 3f; val rx = w - paneW
        panel(c, 0f, 0f, paneW, ph, "Bateria [V]", 0f, 18f, d.battery, d.hBattery, false)
        panel(c, 0f, ph, paneW, ph, "Avanco ignicao [°]", -5f, 45f, d.ignAdv, d.hIgn, false)
        panel(c, 0f, 2 * ph, paneW, ph, "Injecao [ms]", 0f, 20f, d.injDur, d.hInj, false)
        panel(c, rx, 0f, paneW, ph, "MAP [mBar]", 0f, 1200f, d.map, d.hMap, true)
        panel(c, rx, ph, paneW, ph, "Acelerador [%]", 0f, 100f, d.tps, d.hTps, true)
        panel(c, rx, 2 * ph, paneW, ph, "Temp. liquido [°C]", -20f, 120f, d.ect, d.hEct, true)
    }

    private fun panel(c: Canvas, x: Float, y: Float, w: Float, h: Float, title: String,
                      lo: Float, hi: Float, value: Float, hist: History, right: Boolean) {
        val u = h * 0.08f
        val pad = u * 0.32f
        val gTop = y + u * 1.4f; val gBot = y + h - u * 1.8f
        val series = hist.snapshot()
        // grafico
        if (series.size > 1) {
            val gw = w - pad * 2
            val path = Path(); val fill = Path()
            fun yOf(v: Float) = gBot - (v - lo) / (hi - lo) * (gBot - gTop)
            fill.moveTo(x + pad, gBot)
            for (i in series.indices) {
                val px = x + pad + gw * i / (series.size - 1)
                val py = yOf(series[i].coerceIn(lo, hi))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                fill.lineTo(px, py)
            }
            fill.lineTo(x + pad + gw, gBot); fill.close()
            p.style = Paint.Style.FILL; p.color = Color.rgb(0x2d, 0x2d, 0x2d); c.drawPath(fill, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.1f; p.color = Color.rgb(0xa0, 0xa0, 0xa0)
            c.drawPath(path, p)
        }
        // textos
        val tAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        text.textAlign = tAlign; text.typeface = Typeface.DEFAULT
        val tx = if (right) x + w - pad else x + pad
        text.color = Color.rgb(0xe6, 0xe6, 0xe6); text.textSize = u * 0.9f
        c.drawText(title, tx, y + u * 1.1f, text)
        text.color = Color.rgb(0xd0, 0xd0, 0xd0); text.textSize = u * 0.82f
        c.drawText("%.1f".format(hi), tx, y + u * 2.3f, text)
        text.typeface = Typeface.DEFAULT_BOLD; text.color = Color.WHITE; text.textSize = u * 1.9f
        c.drawText("%.1f".format(value), tx, y + u * 4.5f, text)
        text.typeface = Typeface.DEFAULT; text.color = Color.rgb(0xd0, 0xd0, 0xd0); text.textSize = u * 0.82f
        c.drawText("%.1f".format(lo), tx, y + h - u * 0.3f, text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawStatus(c: Canvas, w: Int, h: Int, d: Dash) {
        if (d.connected) return
        text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT
        text.color = Color.rgb(0xaa, 0xaa, 0xaa); text.textSize = h * 0.03f
        c.drawText(d.statusMsg, w / 2f, h * 0.06f, text)
    }
}
