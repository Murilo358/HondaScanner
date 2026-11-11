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

/** Mostra o TRAJETO de uma viagem numa mapa de rua (osmdroid/OSM, sem API key). */
class RouteMapActivity : Activity() {
    private lateinit var map: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        val path = intent.getStringExtra("trip")
        if (path == null) { finish(); return }
        val trip = Trip.load(File(path))
        val pts = trip.route.map { GeoPoint(it.lat, it.lon) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0A141D")) }
        root.addView(TextView(this).apply {
            text = "‹  Trajeto"; setTextColor(Color.parseColor("#C9F2F4")); textSize = 17f
            setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(Color.parseColor("#0F1C27")); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { finish() }
        })
        map = MapView(this).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); setBackgroundColor(Color.parseColor("#0D1620")) }
        root.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

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
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#06111A"); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val p = Point()
        override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
            if (shadow) return
            osmv.projection.toPixels(start, p); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, g); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, edge)
            osmv.projection.toPixels(end, p); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, r); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 11f, edge)
        }
    }

    override fun onResume() { super.onResume(); if (this::map.isInitialized) map.onResume() }
    override fun onPause() { super.onPause(); if (this::map.isInitialized) map.onPause() }
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
}
