package net.murilo.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.drawing.MapSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.min

/** Gera um CARTÃO (imagem PNG) da viagem p/ compartilhar: mapa de rua (OSM) com o trajeto + números.
 *  Baixa tiles da internet — chamar FORA da thread de UI. Sem internet, cai no traçado simples. */
object TripImage {
    private const val BG = "#0A141D"
    private const val PANEL = "#0F1C27"
    private const val ROUTE = "#E23C3C"
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

        // ---- mapa do trajeto ----
        drawRouteCard(ctx, c, RectF(40f, 210f, wf - 40f, 1000f), trip, p)

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

    /** Painel do trajeto: mapa de rua quando os tiles vêm; senão o traçado simples. */
    private fun drawRouteCard(ctx: Context, c: Canvas, r: RectF, trip: Trip, p: Paint) {
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
        val snap = mapSnapshot(ctx, r.width().toInt(), r.height().toInt(), pts)
        if (snap != null) {
            val (mapBmp, proj) = snap
            c.drawBitmap(mapBmp, r.left, r.top, null)
            drawPolyline(c, pts, p, casing = true) { la, lo ->
                val pt = proj.toPixels(GeoPoint(la, lo), null)
                floatArrayOf(r.left + pt.x, r.top + pt.y)
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

    /** Tiles do OSM renderizados no tamanho do painel (bloqueia até ~12 s); null se não vierem. */
    private fun mapSnapshot(ctx: Context, w: Int, h: Int, pts: List<TripSample>): Pair<Bitmap, Projection>? {
        var provider: MapTileProviderBasic? = null
        return try {
            Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            Configuration.getInstance().userAgentValue = ctx.packageName
            val bbox = BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.lat, it.lon) })
            var zoom = MapView.getTileSystem().getBoundingBoxZoom(bbox, w - 110, h - 110)
            if (zoom.isNaN() || zoom <= 0) zoom = 16.0
            zoom = zoom.coerceIn(3.0, 18.0)
            val proj = Projection(zoom, w, h, GeoPoint(bbox.centerLatitude, bbox.centerLongitude),
                0f, true, true, 0, 0)
            val prov = MapTileProviderBasic(ctx, TileSourceFactory.MAPNIK)
            provider = prov
            var out: Bitmap? = null
            val done = CountDownLatch(1)
            val snap = MapSnapshot(MapSnapshot.MapSnapshotable { s ->
                if (s.status == MapSnapshot.Status.CANVAS_OK) {
                    out = s.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    done.countDown()
                }
            }, MapSnapshot.INCLUDE_FLAG_UPTODATE, prov, emptyList(), proj)
            Thread(snap).start()
            done.await(12, TimeUnit.SECONDS)
            out?.let { it to proj }
        } catch (_: Throwable) {
            null
        } finally {
            try { provider?.detach() } catch (_: Exception) {}
        }
    }

    private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
