package net.murilo.scanner

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mapa de rua (osmdroid/OSM, sem API key): trajeto de uma viagem, ou — com extras
 *  lat/lon/when — o ponto onde o carro parou da última vez ("Onde estacionei"). */
class RouteMapActivity : Activity() {
    private lateinit var map: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        val spotLat = intent.getDoubleExtra("lat", Double.NaN)
        val path = intent.getStringExtra("trip")
        if (spotLat.isNaN() && path == null) { finish(); return }

        val title: String
        if (!spotLat.isNaN()) {
            val t = intent.getLongExtra("when", 0L)
            title = "‹  Onde estacionei" +
                if (t > 0) "  ·  " + SimpleDateFormat("EEE HH:mm", Locale("pt", "BR")).format(Date(t)) else ""
        } else title = "‹  Trajeto"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B0A0A")) }
        root.addView(TextView(this).apply {
            text = title; setTextColor(Color.parseColor("#F2EDEB")); textSize = 17f
            setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(Color.parseColor("#141414")); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { finish() }
        })
        map = MapView(this).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); setBackgroundColor(Color.parseColor("#101010")) }
        root.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (!spotLat.isNaN()) {
            val spot = GeoPoint(spotLat, intent.getDoubleExtra("lon", 0.0))
            map.overlays.add(SpotOverlay(spot))
            map.controller.setZoom(17.5); map.controller.setCenter(spot)
            return
        }

        val trip = Trip.of(File(path!!))
        val pts = trip.route.map { GeoPoint(it.lat, it.lon) }
        if (pts.size < 2) { map.controller.setZoom(5.0); map.controller.setCenter(GeoPoint(-15.78, -47.93)); return }

        val pl = Polyline().apply { setPoints(pts); outlinePaint.color = Color.parseColor("#E23C3C"); outlinePaint.strokeWidth = 8f }
        map.overlays.add(pl)
        map.overlays.add(EndsOverlay(pts.first(), pts.last()))
        map.post {
            try { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), false, dp(40)) } catch (_: Exception) { map.controller.setZoom(14.0); map.controller.setCenter(pts.first()) }
        }
    }

    private class EndsOverlay(val start: GeoPoint, val end: GeoPoint) : Overlay() {
        private val g = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2DC06E"); style = Paint.Style.FILL }
        private val r = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E23C3C"); style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B0A0A"); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val p = Point()
        override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
            if (shadow) return
            osmv.projection.toPixels(start, p); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, g); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, edge)
            osmv.projection.toPixels(end, p); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, r); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, edge)
        }
    }

    /** Pino do "Onde estacionei": alvo vermelho com borda branca. */
    private class SpotOverlay(val spot: GeoPoint) : Overlay() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E23C3C"); style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33E23C3C; style = Paint.Style.FILL }
        private val p = Point()
        override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
            if (shadow) return
            osmv.projection.toPixels(spot, p)
            val x = p.x.toFloat(); val y = p.y.toFloat()
            c.drawCircle(x, y, 46f, halo)
            c.drawCircle(x, y, 16f, fill)
            c.drawCircle(x, y, 16f, edge)
        }
    }

    override fun onResume() { super.onResume(); if (this::map.isInitialized) map.onResume() }
    override fun onPause() { super.onPause(); if (this::map.isInitialized) map.onPause() }
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
}
