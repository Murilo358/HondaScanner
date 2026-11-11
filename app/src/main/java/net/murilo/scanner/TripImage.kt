package net.murilo.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/** Gera um CARTÃO (imagem PNG) da viagem p/ compartilhar: mapa de rua (OSM) com o trajeto + números.
 *  Os tiles são baixados direto (Web Mercator na mão — o MapSnapshot do osmdroid não entregava).
 *  Baixa da internet: chamar FORA da thread de UI. Sem internet, cai no traçado simples. */
object TripImage {
    private const val BG = "#0B0A0A"
    private const val PANEL = "#141414"
    private const val ROUTE = "#E23C3C"
    private const val TEXT = "#F2EDEB"
    private const val DIM = "#9A9A9E"

    fun render(ctx: Context, trip: Trip): Bitmap {
        val w = 1080; val h = 1400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor(BG))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val wf = w.toFloat()

        // ---- cabeçalho ----
        p.color = Color.parseColor(TEXT); p.textSize = 58f; p.isFakeBoldText = true
        p.textAlign = Paint.Align.LEFT
        c.drawText("Civic LX", 60f, 110f, p)
        p.isFakeBoldText = false; p.color = Color.parseColor(DIM); p.textSize = 38f
        val d = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.getDefault()).format(Date(trip.startMs))
        c.drawText(d, 60f, 165f, p)
        // logo do app (grifo) no canto
        try {
            val logo = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.gauge_griffin)
            logo?.setBounds(w - 185, 40, w - 55, 170)
            logo?.colorFilter = android.graphics.PorterDuffColorFilter(
                Color.parseColor(ROUTE), android.graphics.PorterDuff.Mode.MULTIPLY)
            logo?.draw(c)
        } catch (_: Exception) {}

        // ---- mapa do trajeto ----
        drawRouteCard(c, RectF(40f, 210f, wf - 40f, 1000f), trip, p)

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

    /** Painel do trajeto: mapa de rua quando os tiles vêm; senão o traçado simples no escuro. */
    private fun drawRouteCard(c: Canvas, r: RectF, trip: Trip, p: Paint) {
        p.style = Paint.Style.FILL
        p.color = Color.parseColor(PANEL); c.drawRoundRect(r, 28f, 28f, p)
        val pts = trip.route
        if (pts.size < 2) {
            p.color = Color.parseColor(DIM); p.textSize = 42f; p.textAlign = Paint.Align.CENTER
            c.drawText("Sem GPS nesta viagem", r.centerX(), r.centerY(), p)
            return
        }
        val clip = Path().apply { addRoundRect(r, 28f, 28f, Path.Direction.CW) }
        c.save(); c.clipPath(clip)
        val snap = osmMap(r.width().toInt(), r.height().toInt(), pts)
        if (snap != null) {
            val (mapBmp, toPx) = snap
            c.drawBitmap(mapBmp, r.left, r.top, null)
            drawPolyline(c, pts, p, casing = true) { la, lo ->
                val q = toPx(la, lo); floatArrayOf(r.left + q[0], r.top + q[1])
            }
        } else {
            // offline/tiles indisponíveis: projeção plana sobre o painel escuro
            var minLa = 90.0; var maxLa = -90.0; var minLo = 180.0; var maxLo = -180.0
            for (s in pts) { minLa = minOf(minLa, s.lat); maxLa = maxOf(maxLa, s.lat); minLo = minOf(minLo, s.lon); maxLo = maxOf(maxLo, s.lon) }
            val midLa = (minLa + maxLa) / 2; val midLo = (minLo + maxLo) / 2
            val kx = cos(Math.toRadians(midLa))
            val xRange = ((maxLo - minLo) * kx).coerceAtLeast(1e-6)
            val yRange = (maxLa - minLa).coerceAtLeast(1e-6)
            val pad = 60f
            val scale = min((r.width() - 2 * pad) / xRange, (r.height() - 2 * pad) / yRange)
            drawPolyline(c, pts, p, casing = false) { la, lo ->
                floatArrayOf(
                    (r.centerX() + ((lo - midLo) * kx * scale)).toFloat(),
                    (r.centerY() - ((la - midLa) * scale)).toFloat()
                )
            }
        }
        c.restore()
    }

    private inline fun drawPolyline(c: Canvas, pts: List<TripSample>, p: Paint,
                                    casing: Boolean, px: (Double, Double) -> FloatArray) {
        val path = Path()
        val first = px(pts[0].lat, pts[0].lon); path.moveTo(first[0], first[1])
        for (i in 1 until pts.size) { val q = px(pts[i].lat, pts[i].lon); path.lineTo(q[0], q[1]) }
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        if (casing) { p.strokeWidth = 18f; p.color = Color.WHITE; c.drawPath(path, p) }  // contorno p/ ler no mapa claro
        p.strokeWidth = 11f; p.color = Color.parseColor(ROUTE); c.drawPath(path, p)
        p.style = Paint.Style.FILL
        val a = px(pts.first().lat, pts.first().lon); val b = px(pts.last().lat, pts.last().lon)
        p.color = Color.WHITE; c.drawCircle(a[0], a[1], 21f, p); c.drawCircle(b[0], b[1], 21f, p)
        p.color = Color.parseColor("#2DC06E"); c.drawCircle(a[0], a[1], 15f, p)
        p.color = Color.parseColor("#E23C3C"); c.drawCircle(b[0], b[1], 15f, p)
    }

    // --- mapa de rua na mão: tiles 256px do OSM + projeção Web Mercator ---

    private fun mx(lon: Double) = (lon + 180.0) / 360.0
    private fun my(lat: Double): Double {
        val rad = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return (1 - ln(tan(rad) + 1 / cos(rad)) / PI) / 2
    }

    /** Bitmap w×h com os tiles cobrindo os pontos + função (lat,lon)→pixel. Null se nenhum tile veio. */
    private fun osmMap(w: Int, h: Int, pts: List<TripSample>): Pair<Bitmap, (Double, Double) -> FloatArray>? {
        return try {
            var minLa = 90.0; var maxLa = -90.0; var minLo = 180.0; var maxLo = -180.0
            for (s in pts) { minLa = minOf(minLa, s.lat); maxLa = maxOf(maxLa, s.lat); minLo = minOf(minLo, s.lon); maxLo = maxOf(maxLo, s.lon) }
            val fx = mx(maxLo) - mx(minLo); val fy = my(minLa) - my(maxLa)
            var z = 18
            while (z > 3 && (fx * 256.0 * (1 shl z) > w - 120 || fy * 256.0 * (1 shl z) > h - 120)) z--
            val world = 256.0 * (1 shl z)
            val left = (mx(minLo) + mx(maxLo)) / 2 * world - w / 2.0
            val top = (my(minLa) + my(maxLa)) / 2 * world - h / 2.0

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.parseColor(PANEL))
            val n = 1 shl z
            val jobs = ArrayList<Callable<Triple<Int, Int, Bitmap?>>>()
            for (tx in Math.floorDiv(left.toInt(), 256)..Math.floorDiv((left + w).toInt(), 256))
                for (ty in Math.floorDiv(top.toInt(), 256)..Math.floorDiv((top + h).toInt(), 256)) {
                    if (ty < 0 || ty >= n) continue
                    val wx = ((tx % n) + n) % n
                    jobs.add(Callable { Triple(tx, ty, fetchTile(z, wx, ty)) })
                }
            val pool = Executors.newFixedThreadPool(2)   // política do OSM: no máx 2 conexões
            var got = 0
            try {
                for (f in pool.invokeAll(jobs, 15, TimeUnit.SECONDS)) {
                    val (tx, ty, tile) = try { f.get() } catch (_: Exception) { continue }
                    if (tile != null) {
                        c.drawBitmap(tile, (tx * 256 - left).toFloat(), (ty * 256 - top).toFloat(), null)
                        got++
                    }
                }
            } finally { pool.shutdownNow() }
            if (got == 0) return null
            bmp to { la: Double, lo: Double ->
                floatArrayOf((mx(lo) * world - left).toFloat(), (my(la) * world - top).toFloat())
            }
        } catch (_: Throwable) { null }
    }

    private fun fetchTile(z: Int, x: Int, y: Int): Bitmap? = try {
        val u = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        u.setRequestProperty("User-Agent", "net.murilo.scanner")   // política do OSM: UA identificável
        u.connectTimeout = 5000; u.readTimeout = 8000
        u.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
