package net.murilo.scanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro estilo Hondash: gráficos empilhados roláveis, cursor na timeline, esticar com pinça. */
class DatalogActivity : Activity() {
    private lateinit var trip: Trip
    private lateinit var graph: DatalogView
    private lateinit var readout: TextView
    private lateinit var seek: SeekBar
    private var tripPath: String = ""

    private val CYAN = Color.parseColor("#C9F2F4"); private val DIM = Color.parseColor("#8a8c92")

    private val channels by lazy {
        listOf(
            Channel("rpm", "RPM", "", Color.parseColor("#E23C3C"), { it.rpm }, 0f, null),
            Channel("speed", "Velocidade", "km/h", Color.parseColor("#E8EDF1"), { it.speed }, 0f, null),
            Channel("ect", "Água", "°C", Color.parseColor("#2DC06E"), { it.ect }),
            Channel("tps", "Acelerador", "%", Color.parseColor("#E0A32E"), { it.tps }, 0f, 100f),
            Channel("map", "MAP", "", Color.parseColor("#4a8ad6"), { it.map }),
            Channel("lambda", "Lambda", "", Color.parseColor("#B388FF"), { it.lambda }, decimals = 2),
            Channel("iat", "Ar adm.", "°C", Color.parseColor("#4DD0E1"), { it.iat }, on = false),
            Channel("battery", "Bateria", "V", Color.parseColor("#9aa0a6"), { it.battery }, decimals = 1, on = false),
            Channel("gear", "Marcha", "", Color.parseColor("#FF9100"), { it.gear.toFloat() }, 0f, 6f, on = false),
            Channel("economy", "Consumo", "km/L", Color.parseColor("#9CCC65"), { it.economy }, 0f, null, decimals = 1, on = false)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        tripPath = intent.getStringExtra("trip") ?: ""
        if (tripPath.isEmpty()) { finish(); return }
        trip = Trip.load(File(tripPath))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#171719")); setPadding(dp(10), dp(8), dp(10), dp(8)) }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = summaryTitle(); setTextColor(CYAN); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        })
        if (trip.hasGps) head.addView(ghostBtn("Ver no mapa") {
            startActivity(Intent(this, RouteMapActivity::class.java).putExtra("trip", tripPath))
        })
        head.addView(ghostBtn("Compartilhar") { shareImage() })
        root.addView(head)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (ch in channels) chips.addView(channelChip(ch))
        root.addView(HorizontalScrollView(this).apply { addView(chips); isHorizontalScrollBarEnabled = false }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(4) })

        readout = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(2), dp(2), dp(2), dp(6)) }
        root.addView(readout)

        graph = DatalogView(this).apply {
            this.trip = this@DatalogActivity.trip
            this.channels = this@DatalogActivity.channels
            onScrub = { i -> syncTo(i) }
        }
        // ScrollView vertical -> os gráficos ficam altos e rolam; arrasto horizontal no gráfico faz o scrub
        root.addView(ScrollView(this).apply { isFillViewport = false; addView(graph) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        seek = SeekBar(this).apply {
            max = (trip.samples.size - 1).coerceAtLeast(0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { graph.index = p; readout.text = readoutText(p) } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        setContentView(root)
        if (trip.samples.isNotEmpty()) syncTo(0)
        toast("Arraste no gráfico = cursor · pinça estica (tempo / altura) · 2 toques = normal")
    }

    private fun syncTo(i: Int) { seek.progress = i; readout.text = readoutText(i) }

    private fun summaryTitle(): String {
        val d = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(trip.startMs))
        val dur = trip.durationMs / 1000
        return "‹ Viagem $d · ${"%.1f".format(trip.distanceKm)} km · %d:%02d".format(dur / 60, dur % 60)
    }

    private fun readoutText(i: Int): String {
        val s = trip.samples.getOrNull(i) ?: return ""
        val rel = (s.t - trip.startMs) / 1000
        val g = if (s.gear > 0) "${s.gear}ª" else if (s.gear == 0) "N" else "-"
        return "%d:%02d  ·  %d km/h  ·  %d rpm  ·  %s  ·  %d°  ·  tps %d%%  ·  λ%.2f".format(
            rel / 60, rel % 60, s.speed.toInt(), s.rpm.toInt(), g, s.ect.toInt(), s.tps.toInt(), s.lambda)
    }

    private fun channelChip(ch: Channel): Button {
        val b = Button(this).apply {
            isAllCaps = false; textSize = 12.5f; setPadding(dp(12), dp(2), dp(12), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
        }
        fun style() {
            b.text = (if (ch.on) "● " else "○ ") + ch.name
            b.setTextColor(if (ch.on) Color.BLACK else Color.parseColor("#b8bcc2"))
            b.background = pill(if (ch.on) ch.color else Color.parseColor("#2a2a2e"))
        }
        b.setOnClickListener {
            if (ch.on && channels.count { it.on } <= 1) { toast("Deixe ao menos um canal"); return@setOnClickListener }
            ch.on = !ch.on; style(); graph.markDirty()
        }
        style(); return b
    }

    private fun shareImage() {
        try {
            val bmp = TripImage.render(trip)
            val dir = File(cacheDir, "shared"); dir.mkdirs()
            val f = File(dir, "viagem.png")
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Compartilhar viagem"))
        } catch (e: Exception) { toast("Falha ao gerar imagem") }
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(color) }
    private fun ghostBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(CYAN)
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#212023")) }
        setPadding(dp(14), dp(5), dp(14), dp(5))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) }
        setOnClickListener { onClick() }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
}
