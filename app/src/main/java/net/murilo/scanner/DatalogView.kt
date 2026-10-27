package net.murilo.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
 * Gráficos empilhados do datalog (estilo Hondash), ROLÁVEIS (vai dentro de um ScrollView) e que se
 * ESTICAM com pinça: horizontal = mais detalhe no tempo, vertical = faixas mais altas. Arraste com um
 * dedo na horizontal = cursor (scrub); na vertical = rola a lista. Toque duplo = volta ao normal.
 */
class DatalogView(context: Context) : View(context) {
    var trip: Trip? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var channels: List<Channel> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var index = 0
        set(v) { field = v.coerceIn(0, ((trip?.samples?.size ?: 1) - 1).coerceAtLeast(0)); invalidate() }
    var onScrub: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val defLaneH = 118f * d
    private var laneH = defLaneH
    private val minLaneH = 72f * d
    private val maxLaneH = 320f * d
    private var timeZoom = 1f          // 1 = viagem inteira; >1 = janela menor (mais detalhe)
    private var winStart = 0f          // fração 0..(1-1/timeZoom) do início da janela visível
    private val labelW = 66f * d
    private val padR = 10f * d

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1b1a1f"); strokeWidth = 1f }
    private val bandBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E23C3C"); strokeWidth = 1.6f * d }
    private val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8a8c92"); textSize = 11f * d }
    private val valP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f * d; typeface = android.graphics.Typeface.MONOSPACE }
    private val axisP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6f7479"); textSize = 10f * d; typeface = android.graphics.Typeface.MONOSPACE }

    private fun active() = channels.filter { it.on }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val n = active().size.coerceAtLeast(1)
        setMeasuredDimension(w, (laneH * n).toInt().coerceAtLeast((laneH).toInt()))
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#171719"))
        val t = trip ?: return
        val s = t.samples
        val act = active()
        if (s.size < 2 || act.isEmpty()) {
            nameP.textSize = 13f * d; c.drawText("Sem dados", labelW, height / 2f, nameP); nameP.textSize = 11f * d; return
        }
        val t0 = s.first().t.toDouble(); val t1 = s.last().t.toDouble().coerceAtLeast(t0 + 1)
        val span = (t1 - t0) / timeZoom
        val wt0 = t0 + winStart * (t1 - t0)
        val wt1 = (wt0 + span).coerceAtMost(t1)
        val gl = labelW; val gr = width - padR; val gw = (gr - gl).coerceAtLeast(1f)
        fun xOf(tt: Double) = gl + (((tt - wt0) / (wt1 - wt0)) * gw).toFloat()
        val cur = s.getOrNull(index)

        for ((bi, ch) in act.withIndex()) {
            val top = bi * laneH; val bot = top + laneH
            bandBg.color = if (bi % 2 == 0) Color.parseColor("#17171a") else Color.parseColor("#131214")
            c.drawRect(0f, top, width.toFloat(), bot, bandBg)
            c.drawLine(0f, bot, width.toFloat(), bot, grid)
            // min/max sobre a viagem toda (estável)
            var mn = ch.fixedMin ?: Float.MAX_VALUE; var mx = ch.fixedMax ?: -Float.MAX_VALUE
            if (ch.fixedMin == null || ch.fixedMax == null) for (sm in s) { val v = ch.get(sm); if (ch.fixedMin == null && v < mn) mn = v; if (ch.fixedMax == null && v > mx) mx = v }
            if (mx - mn < 1e-3f) mx = mn + 1f
            val gTop = top + 20f * d; val gBot = bot - 8f * d
            fun yOf(v: Float) = gBot - ((v - mn) / (mx - mn)) * (gBot - gTop)
            // linha (só amostras na janela + vizinhas)
            line.color = ch.color
            val path = Path(); var started = false
            val step = ((s.size / timeZoom) / gw).toInt().coerceAtLeast(1)
            var i = 0
            while (i < s.size) {
                val sm = s[i]
                if (sm.t >= wt0 - span * 0.02 && sm.t <= wt1 + span * 0.02) {
                    val x = xOf(sm.t.toDouble()); val y = yOf(ch.get(sm))
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                i += step
            }
            c.drawPath(path, line)
            // rótulo + valor no cursor
            c.drawText(ch.name, 8f * d, top + 15f * d, nameP)
            if (cur != null) {
                valP.color = ch.color
                val v = ch.get(cur)
                c.drawText(if (ch.decimals == 0) v.toInt().toString() else "%.${ch.decimals}f".format(v), 8f * d, bot - 12f * d, valP)
            }
        }
        // cursor (se estiver na janela)
        if (cur != null && cur.t >= wt0 && cur.t <= wt1) {
            val x = xOf(cur.t.toDouble())
            c.drawLine(x, 0f, x, height.toFloat(), cursor)
        }
        // tempo nos cantos da janela
        axisP.textAlign = Paint.Align.LEFT; c.drawText(fmtT(((wt0 - t0) / 1000).toLong()), gl, height - 4f * d, axisP)
        axisP.textAlign = Paint.Align.RIGHT; c.drawText(fmtT(((wt1 - t0) / 1000).toLong()), gr, height - 4f * d, axisP)
        axisP.textAlign = Paint.Align.LEFT
    }

    private fun fmtT(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)

    // -------- gestos --------
    private var prevSpanX = 0f; private var prevSpanY = 0f
    private val scaleDet = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(det: ScaleGestureDetector): Boolean { prevSpanX = det.currentSpanX; prevSpanY = det.currentSpanY; parent?.requestDisallowInterceptTouchEvent(true); return true }
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val sx = det.currentSpanX; val sy = det.currentSpanY
            if (prevSpanX > 8f && sx > 8f) {
                val rx = (sx / prevSpanX).coerceIn(0.7f, 1.4f)
                val oldZoom = timeZoom
                timeZoom = (timeZoom * rx).coerceIn(1f, 24f)
                // mantém o cursor no lugar ao zoomar
                val s = trip?.samples
                if (s != null && s.size > 1) {
                    val t0 = s.first().t.toDouble(); val t1 = s.last().t.toDouble().coerceAtLeast(t0 + 1)
                    val cfrac = ((s.getOrNull(index)?.t ?: s.first().t).toDouble() - t0) / (t1 - t0)
                    winStart = (cfrac - 0.5f / timeZoom).toFloat().coerceIn(0f, 1f - 1f / timeZoom)
                }
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
        override fun onDoubleTap(e: MotionEvent): Boolean { timeZoom = 1f; winStart = 0f; laneH = defLaneH; requestLayout(); invalidate(); return true }
    })

    private var downX = 0f; private var downY = 0f; private var mode = 0  // 0 indef, 1 horiz(scrub), 2 vert(scroll)
    private val slop = 10f * d

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e); tapDet.onTouchEvent(e)
        if (scaleDet.isInProgress) { mode = 0; return true }
        val s = trip?.samples ?: return false
        if (s.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; mode = 0; return true }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1) return true
                if (mode == 0) {
                    val dx = Math.abs(e.x - downX); val dy = Math.abs(e.y - downY)
                    if (dx > slop || dy > slop) mode = if (dx > dy) 1 else 2
                }
                if (mode == 1) { parent?.requestDisallowInterceptTouchEvent(true); scrubTo(e.x); return true }
                return false  // vertical -> deixa o ScrollView rolar
            }
        }
        return true
    }

    private fun scrubTo(x: Float) {
        val s = trip?.samples ?: return
        val t0 = s.first().t.toDouble(); val t1 = s.last().t.toDouble().coerceAtLeast(t0 + 1)
        val span = (t1 - t0) / timeZoom
        val wt0 = t0 + winStart * (t1 - t0)
        val gl = labelW; val gr = width - padR
        val frac = ((x - gl) / (gr - gl)).coerceIn(0f, 1f)
        val targetT = wt0 + frac * span
        var lo = 0; var hi = s.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (s[mid].t < targetT) lo = mid + 1 else hi = mid }
        index = lo; onScrub?.invoke(lo)
        // segue o cursor se chegou na borda da janela
        val cfrac = ((s[lo].t.toDouble() - t0) / (t1 - t0)).toFloat()
        if (timeZoom > 1f) {
            if (cfrac < winStart + 0.08f / timeZoom || cfrac > winStart + 0.92f / timeZoom)
                winStart = (cfrac - 0.5f / timeZoom).coerceIn(0f, 1f - 1f / timeZoom)
        }
        invalidate()
    }

    fun markDirty() { requestLayout(); invalidate() }
}
