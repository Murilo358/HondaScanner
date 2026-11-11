package net.murilo.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min

/** Gera um CARTÃO (imagem PNG) da viagem p/ compartilhar: trajeto + números. Mesma linguagem do painel. */
object TripImage {
    private const val BG = "#0A141D"
    private const val PANEL = "#0F1C27"
    private const val RING = "#2B82D8"
    private const val CYAN = "#C9F2F4"
    private const val DIM = "#8FB8C8"

    fun render(ctx: Context, trip: Trip): Bitmap {
        val w = 1080; val h = 1400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor(BG))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val wf = w.toFloat()

        // ---- cabeçalho ----
        p.color = Color.parseColor(CYAN); p.textSize = 58f; p.isFakeBoldText = true
        p.textAlign = Paint.Align.LEFT
        c.drawText("Civic LX", 60f, 110f, p)
        p.isFakeBoldText = false; p.color = Color.parseColor(DIM); p.textSize = 38f
        val d = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.getDefault()).format(Date(trip.startMs))
        c.drawText(d, 60f, 165f, p)
        // logo do app (grifo) no canto
        try {
            val logo = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.gauge_griffin)
            logo?.setBounds(w - 185, 40, w - 55, 170)
            logo?.draw(c)
        } catch (_: Exception) {}

        // ---- mapa do trajeto (painel) ----
        val mapTop = 210f; val mapBot = 1000f
        val panel = RectF(40f, mapTop, wf - 40f, mapBot)
        p.color = Color.parseColor(PANEL); c.drawRoundRect(panel, 28f, 28f, p)
        drawRoute(c, panel, trip, p)

        // ---- números ----
        val dur = fmtDur(trip.durationMs)
        val cells = listOf(
            "DISTÂNCIA" to "%.1f km".format(trip.distanceKm),
            "DURAÇÃO" to dur,
            "VEL. MÁX" to "${trip.maxSpeed.toInt()} km/h",
            "RPM MÁX" to "${trip.maxRpm.toInt()}",
            "MÉDIA" to "%.1f km/L".format(trip.avgKmL),
            "COMBUSTÍVEL" to "%.1f L".format(trip.fuelL)
        )
        val gridTop = 1035f; val colW = (wf - 80f) / 3f; val rowH = 150f
        for (i in cells.indices) {
            val col = i % 3; val row = i / 3
            val cx = 40f + col * colW + colW / 2f
            val cy = gridTop + row * rowH
            p.textAlign = Paint.Align.CENTER
            p.color = Color.parseColor(DIM); p.textSize = 30f
            c.drawText(cells[i].first, cx, cy, p)
            p.color = Color.WHITE; p.textSize = 54f; p.isFakeBoldText = true
            c.drawText(cells[i].second, cx, cy + 58f, p)
            p.isFakeBoldText = false
        }

        // ---- rodapé ----
        p.textAlign = Paint.Align.CENTER; p.color = Color.parseColor(DIM); p.textSize = 28f
        c.drawText("Scanner", wf / 2f, h - 36f, p)
        return bmp
    }

    /** Desenha a polyline do GPS dentro do retângulo dado (mesma projeção do TripReplayView). */
    private fun drawRoute(c: Canvas, r: RectF, trip: Trip, p: Paint) {
        val pts = trip.samples.filter { it.lat != 0.0 || it.lon != 0.0 }
        if (pts.size < 2) {
            p.color = Color.parseColor(DIM); p.textSize = 42f; p.textAlign = Paint.Align.CENTER
            c.drawText("Sem GPS nesta viagem", r.centerX(), r.centerY(), p)
            return
        }
        var minLa = 90.0; var maxLa = -90.0; var minLo = 180.0; var maxLo = -180.0
        for (s in pts) { minLa = minOf(minLa, s.lat); maxLa = maxOf(maxLa, s.lat); minLo = minOf(minLo, s.lon); maxLo = maxOf(maxLo, s.lon) }
        val midLa = (minLa + maxLa) / 2; val midLo = (minLo + maxLo) / 2
        val kx = cos(Math.toRadians(midLa))
        val xRange = ((maxLo - minLo) * kx).coerceAtLeast(1e-6)
        val yRange = (maxLa - minLa).coerceAtLeast(1e-6)
        val pad = 60f
        val scale = min((r.width() - 2 * pad) / xRange, (r.height() - 2 * pad) / yRange)
        fun px(lo: Double) = (r.centerX() + ((lo - midLo) * kx * scale)).toFloat()
        fun py(la: Double) = (r.centerY() - ((la - midLa) * scale)).toFloat()

        p.style = Paint.Style.STROKE; p.strokeWidth = 12f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.color = Color.parseColor(RING)
        val path = Path(); path.moveTo(px(pts[0].lon), py(pts[0].lat))
        for (i in 1 until pts.size) path.lineTo(px(pts[i].lon), py(pts[i].lat))
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#2DC06E"); c.drawCircle(px(pts.first().lon), py(pts.first().lat), 18f, p)
        p.color = Color.parseColor("#E23C3C"); c.drawCircle(px(pts.last().lon), py(pts.last().lat), 18f, p)
    }

    private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
