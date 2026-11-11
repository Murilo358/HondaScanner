package net.murilo.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.min

/** Desenha o TRAJETO da viagem (polyline do GPS) + marcador no ponto atual (scrub). */
class TripReplayView(context: Context) : View(context) {
    var trip: Trip? = null
    var index = 0
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#0A141D"))
        val t = trip ?: return
        val pts = t.route
        val w = width.toFloat(); val h = height.toFloat(); val pad = min(w, h) * 0.08f
        if (pts.size < 2) {
            p.color = Color.parseColor("#8FB8C8"); p.textSize = min(w, h) * 0.05f; p.textAlign = Paint.Align.CENTER
            c.drawText("Sem GPS nesta viagem", w / 2f, h / 2f, p)
            return
        }
        var minLa = 90.0; var maxLa = -90.0; var minLo = 180.0; var maxLo = -180.0
        for (s in pts) { minLa = minOf(minLa, s.lat); maxLa = maxOf(maxLa, s.lat); minLo = minOf(minLo, s.lon); maxLo = maxOf(maxLo, s.lon) }
        val midLa = (minLa + maxLa) / 2; val midLo = (minLo + maxLo) / 2
        val kx = cos(Math.toRadians(midLa))
        val xRange = ((maxLo - minLo) * kx).coerceAtLeast(1e-6)
        val yRange = (maxLa - minLa).coerceAtLeast(1e-6)
        val scale = min((w - 2 * pad) / xRange, (h - 2 * pad) / yRange)
        fun px(lo: Double) = (w / 2f + ((lo - midLo) * kx * scale)).toFloat()
        fun py(la: Double) = (h / 2f - ((la - midLa) * scale)).toFloat()

        // trajeto
        p.style = Paint.Style.STROKE; p.strokeWidth = min(w, h) * 0.012f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.color = Color.parseColor("#2B82D8")
        val path = Path(); path.moveTo(px(pts[0].lon), py(pts[0].lat))
        for (i in 1 until pts.size) path.lineTo(px(pts[i].lon), py(pts[i].lat))
        c.drawPath(path, p)
        // inicio (verde) / fim (vermelho)
        p.style = Paint.Style.FILL
        val r = min(w, h) * 0.018f
        p.color = Color.parseColor("#2DC06E"); c.drawCircle(px(pts.first().lon), py(pts.first().lat), r, p)
        p.color = Color.parseColor("#E23C3C"); c.drawCircle(px(pts.last().lon), py(pts.last().lat), r, p)
        // ponto atual (scrub) — mapeia o index (sobre todas as amostras) p/ o ponto gps mais proximo no tempo
        val cur = t.samples.getOrNull(index)
        if (cur != null) {
            val near = pts.minByOrNull { kotlin.math.abs(it.t - cur.t) }
            if (near != null) {
                p.color = Color.parseColor("#C9F2F4"); c.drawCircle(px(near.lon), py(near.lat), r * 1.4f, p)
                p.color = Color.parseColor("#0A141D"); c.drawCircle(px(near.lon), py(near.lat), r * 0.6f, p)
            }
        }
    }
}
