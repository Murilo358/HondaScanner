package net.murilo.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Um canal do datalog (RPM, velocidade, água…) com cor e faixa. */
class Channel(
    val key: String, val name: String, val unit: String, val color: Int,
    val get: (TripSample) -> Float, val fixedMin: Float? = null, val fixedMax: Float? = null,
    val decimals: Int = 0, var on: Boolean = true
)

/**
 * Gráficos empilhados no modelo do Hondash: CURSOR FIXO NO CENTRO e o gráfico corre por baixo.
 * Arrastar na horizontal anda no tempo; pinça estica (tempo / altura das faixas); 2 toques = normal.
 * Cada faixa: máx em cima e mín embaixo à esquerda, nome [unidade] no meio, valor GRANDE à direita
 * do cursor, linha colorida com preenchimento. Vai dentro de um ScrollView (rola na vertical).
 */
class DatalogView(context: Context) : View(context) {
    var trip: Trip? = null
        set(v) {
            field = v
            // janela inicial ≈ 2 min (como a sensação de uso do Hondash), nunca menos que a viagem toda
            val total = ((v?.durationMs ?: 0L)).coerceAtLeast(1L)
            timeZoom = (total / 120_000f).coerceAtLeast(1f)
            requestLayout(); invalidate(); notifyWindow()
        }
    var channels: List<Channel> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var index = 0
        set(v) { field = v.coerceIn(0, ((trip?.samples?.size ?: 1) - 1).coerceAtLeast(0)); invalidate() }
    var onScrub: ((Int) -> Unit)? = null
    var onWindow: ((visibleMs: Long) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val defLaneH = 96f * d
    private var laneH = defLaneH
    private val minLaneH = 64f * d
    private val maxLaneH = 320f * d
    private var timeZoom = 1f          // 1 = viagem inteira; >1 = janela menor (mais detalhe)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#26262b"); strokeWidth = 1f }
    private val cursorP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E23C3C"); strokeWidth = 2f * d }
    private val mmP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#aeb1b6"); textSize = 11f * d }
    private val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e8e8e8"); textSize = 12.5f * d }
    private val valP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 27f * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun active() = channels.filter { it.on }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val n = active().size.coerceAtLeast(1)
        setMeasuredDimension(w, (laneH * n).toInt().coerceAtLeast(laneH.toInt()))
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#101013"))
        val t = trip ?: return
        val s = t.samples
        val act = active()
        if (s.size < 2 || act.isEmpty()) {
            c.drawText("Sem dados", 16f * d, height / 2f, nameP); return
        }
        val t0 = s.first().t.toDouble(); val t1 = s.last().t.toDouble().coerceAtLeast(t0 + 1)
        val span = ((t1 - t0) / timeZoom).coerceAtLeast(1000.0)
        val tc = s[index.coerceIn(0, s.size - 1)].t.toDouble()
        val wt0 = tc - span / 2; val wt1 = tc + span / 2
        val cx = width / 2f
        fun xOf(tt: Double) = (((tt - wt0) / (wt1 - wt0)) * width).toFloat()

        for ((bi, ch) in act.withIndex()) {
            val top = bi * laneH; val bot = top + laneH
            c.drawLine(0f, bot, width.toFloat(), bot, grid)
            // min/max sobre a viagem toda (estável)
            var mn = ch.fixedMin ?: Float.MAX_VALUE; var mx = ch.fixedMax ?: -Float.MAX_VALUE
            if (ch.fixedMin == null || ch.fixedMax == null)
                for (sm in s) { val v = ch.get(sm); if (ch.fixedMin == null && v < mn) mn = v; if (ch.fixedMax == null && v > mx) mx = v }
            if (mx - mn < 1e-3f) mx = mn + 1f
            val gTop = top + 7f * d; val gBot = bot - 7f * d
            fun yOf(v: Float) = gBot - ((v - mn) / (mx - mn)) * (gBot - gTop)
            // linha + preenchimento (só amostras na janela)
            val path = Path(); var started = false
            var firstX = 0f; var lastX = 0f
            val visible = (s.size / timeZoom).toInt().coerceAtLeast(1)
            val step = (visible / width.coerceAtLeast(1)).coerceAtLeast(1)
            var i = 0
            while (i < s.size) {
                val sm = s[i]
                if (sm.t >= wt0 - span * 0.02 && sm.t <= wt1 + span * 0.02) {
                    val x = xOf(sm.t.toDouble()); val y = yOf(ch.get(sm))
                    if (!started) { path.moveTo(x, y); firstX = x; started = true } else path.lineTo(x, y)
                    lastX = x
                }
                i += step
            }
            if (started) {
                val fp = Path(path)
                fp.lineTo(lastX, gBot); fp.lineTo(firstX, gBot); fp.close()
                fill.color = ch.color and 0x00FFFFFF or (0x3C shl 24)
                c.drawPath(fp, fill)
                line.color = ch.color
                c.drawPath(path, line)
            }
            // rótulos à esquerda: máx / nome [unidade] / mín
            val label = if (ch.unit.isNotEmpty()) "${ch.name}  [${ch.unit}]" else ch.name
            c.drawText(fmt(mx, ch), 7f * d, top + 14f * d, mmP)
            c.drawText(label, 7f * d, top + laneH / 2f + 4.5f * d, nameP)
            c.drawText(fmt(mn, ch), 7f * d, bot - 6f * d, mmP)
            // valor grande à direita do cursor
            c.drawText(fmtVal(ch.get(s[index]), ch), cx + 9f * d, top + 34f * d, valP)
        }
        // cursor fixo no centro
        c.drawLine(cx, 0f, cx, height.toFloat(), cursorP)
    }

    private fun fmt(v: Float, ch: Channel) =
        if (ch.decimals == 0) v.toInt().toString() else "%.${ch.decimals}f".format(v)

    private fun fmtVal(v: Float, ch: Channel): String {
        if (ch.key == "gear") return if (v < 0.5f) "N" else v.toInt().toString()
        return fmt(v, ch)
    }

    fun visibleSpanMs(): Long {
        val t = trip ?: return 0L
        return (t.durationMs / timeZoom).toLong().coerceAtLeast(1000L)
    }

    private fun notifyWindow() { onWindow?.invoke(visibleSpanMs()) }

    // -------- gestos --------
    private var prevSpanX = 0f; private var prevSpanY = 0f
    private val scaleDet = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(det: ScaleGestureDetector): Boolean {
            prevSpanX = det.currentSpanX; prevSpanY = det.currentSpanY
            parent?.requestDisallowInterceptTouchEvent(true); return true
        }
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val sx = det.currentSpanX; val sy = det.currentSpanY
            if (prevSpanX > 8f && sx > 8f) {
                val rx = (sx / prevSpanX).coerceIn(0.7f, 1.4f)
                timeZoom = (timeZoom * rx).coerceIn(1f, 64f)
                notifyWindow()
            }
            if (prevSpanY > 8f && sy > 8f) {
                val ry = (sy / prevSpanY).coerceIn(0.7f, 1.4f)
                laneH = (laneH * ry).coerceIn(minLaneH, maxLaneH)
                requestLayout()
            }
            prevSpanX = sx; prevSpanY = sy
            invalidate()
            return true
        }
    })
    private val tapDet = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val total = (trip?.durationMs ?: 0L).coerceAtLeast(1L)
            timeZoom = (total / 120_000f).coerceAtLeast(1f)
            laneH = defLaneH
            requestLayout(); invalidate(); notifyWindow(); return true
        }
    })

    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var mode = 0  // 0 indef, 1 horiz (anda no tempo), 2 vert (rola a lista)
    private val slop = 10f * d

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e); tapDet.onTouchEvent(e)
        if (scaleDet.isInProgress) { mode = 0; return true }
        val s = trip?.samples ?: return false
        if (s.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; mode = 0; return true }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1) return true
                if (mode == 0) {
                    val dx = Math.abs(e.x - downX); val dy = Math.abs(e.y - downY)
                    if (dx > slop || dy > slop) mode = if (dx > dy) 1 else 2
                }
                if (mode == 1) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    panBy(e.x - lastX); lastX = e.x
                    return true
                }
                return false  // vertical -> deixa o ScrollView rolar
            }
        }
        return true
    }

    /** Arrastou dx px: o gráfico acompanha o dedo (conteúdo corre sob o cursor fixo). */
    private fun panBy(dx: Float) {
        val s = trip?.samples ?: return
        val t0 = s.first().t.toDouble(); val t1 = s.last().t.toDouble().coerceAtLeast(t0 + 1)
        val span = ((t1 - t0) / timeZoom).coerceAtLeast(1000.0)
        val target = (s[index].t.toDouble() - dx * span / width).coerceIn(t0, t1)
        var lo = 0; var hi = s.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (s[mid].t < target) lo = mid + 1 else hi = mid }
        if (lo != index) { index = lo; onScrub?.invoke(lo) }
        invalidate()
    }

    fun markDirty() { requestLayout(); invalidate() }
}
