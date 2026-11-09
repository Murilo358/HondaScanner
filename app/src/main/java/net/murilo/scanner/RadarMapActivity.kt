package net.murilo.scanner

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** Tela de mapa (osmdroid/OSM) p/ VISUALIZAR os radares baixados e conferir se batem com a realidade. */
class RadarMapActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var info: TextView
    private lateinit var warnLine: TextView
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid exige User-Agent próprio, senão o OSM bloqueia os tiles
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }

        root.addView(Ui.header(this, "Radares", null) { finish() }.apply {
            setPadding(pad(16), pad(12), pad(16), pad(4))
        })
        info = TextView(this).apply {
            setTextColor(Ui.MUTED); textSize = 12f; setPadding(pad(18), 0, pad(16), pad(10))
        }
        root.addView(info)

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBackgroundColor(Color.parseColor("#0D1620"))
        }
        root.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad(12), pad(10), pad(12), pad(4)); gravity = Gravity.CENTER }
        bar.addView(Ui.pill(this, "Onde estou", primary = false) { centerOnMe() }, barLp())
        bar.addView(Ui.pill(this, "Atualizar radares", primary = true) { downloadPardal() }, barLp())
        root.addView(bar)

        warnLine = TextView(this).apply {
            textSize = 12f; gravity = Gravity.CENTER; setPadding(pad(16), pad(6), pad(16), pad(10))
            setOnClickListener {
                val on = !Feat.on("radar"); Feat.toggle("radar", on); refreshWarnLine()
            }
        }
        refreshWarnLine()
        root.addView(warnLine)

        setContentView(root)

        map.controller.setZoom(14.0)
        val me = myPoint()
        // centraliza: no GPS; senão onde os radares já baixados estão; senão São Paulo
        val start = me
            ?: RadarDb.center()?.let { GeoPoint(it.first, it.second) }
            ?: GeoPoint(-23.5505, -46.6333)
        map.controller.setCenter(start)
        map.overlays.add(RadarOverlay())
        refreshPlot()

        // pede permissão de localização (p/ "Onde estou" e o aviso de radar funcionarem)
        if (!GpsProvider.hasPerm(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 7)
        }
        // se não tem nada baixado, já puxa a base nacional completa (não precisa de GPS)
        if (RadarDb.size() == 0) downloadPardal()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Shared.dash?.let { GpsProvider.start(this, it) }
            toast("Localização liberada")
        }
    }

    private fun myPoint(): GeoPoint? {
        val d = Shared.dash ?: return null
        return if (d.gpsLat != 0.0 || d.gpsLon != 0.0) GeoPoint(d.gpsLat, d.gpsLon) else null
    }

    private fun centerOnMe() {
        val me = myPoint()
        if (me == null) { toast("Sem GPS ainda — ande um pouco com o app aberto"); return }
        map.controller.animateTo(me); map.controller.setZoom(16.0)
    }

    private fun downloadPardal() {
        RadarDb.refreshPardal()
        toast("Atualizando radares (Brasil inteiro)…")
        pollWhileLoading()
    }

    /** Re-plota enquanto o download (assíncrono) não termina. */
    private fun pollWhileLoading() {
        var tries = 0
        val tick = object : Runnable {
            override fun run() {
                refreshPlot(); tries++
                if (RadarDb.loading && tries < 30) h.postDelayed(this, 1000) else refreshPlot()
            }
        }
        h.postDelayed(tick, 1000)
    }

    private fun refreshPlot() {
        val n = RadarDb.size()
        val me = if (myPoint() != null) "  ·  GPS ok" else "  ·  sem GPS"
        info.text = if (RadarDb.loading) "Baixando radares…  (tenho $n)$me"
                    else "Radares carregados: $n$me" + if (n == 0) "  — toque em “Baixar aqui”" else ""
        map.invalidate()
    }

    private fun barLp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(pad(4), 0, pad(4), 0)
    }

    private fun refreshWarnLine() {
        val on = Feat.on("radar")
        warnLine.setTextColor(if (on) Ui.FAINT else Ui.WARN)
        warnLine.text = if (on) "Aviso sonoro e no painel quando um radar estiver à frente: ligado — toque para desligar"
                        else "Aviso de radar desligado — toque para ligar"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun pad(dp: Int) = Math.round(dp * resources.displayMetrics.density)

    override fun onResume() { super.onResume(); map.onResume(); refreshPlot() }
    override fun onPause() { super.onPause(); map.onPause() }

    /** Desenha os radares (pontos vermelhos) e a minha posição (ponto ciano) — leve, sem Marker por ponto. */
    private inner class RadarOverlay : Overlay() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E23C3C"); style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#06111A"); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val meFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9F2F4"); style = Paint.Style.FILL }
        private val meEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2B82D8"); style = Paint.Style.STROKE; strokeWidth = 4f }
        private val p = Point()

        override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
            if (shadow) return
            val proj = osmv.projection
            val bb = osmv.boundingBox
            val rPx = 7f * resources.displayMetrics.density / 2f + 4f
            for (r in RadarDb.all()) {
                if (r.lat > bb.latNorth || r.lat < bb.latSouth || r.lon > bb.lonEast || r.lon < bb.lonWest) continue
                proj.toPixels(GeoPoint(r.lat, r.lon), p)
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), rPx, fill)
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), rPx, edge)
            }
            val me = myPoint()
            if (me != null) {
                proj.toPixels(me, p)
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), rPx + 3f, meFill)
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), rPx + 3f, meEdge)
            }
        }
    }
}
