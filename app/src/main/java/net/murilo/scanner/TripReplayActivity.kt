package net.murilo.scanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.core.content.FileProvider
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Replay de uma viagem: trajeto + linha do tempo (scrub) mostrando o estado do carro em cada ponto. */
class TripReplayActivity : Activity() {
    private lateinit var trip: Trip
    private lateinit var view: TripReplayView
    private lateinit var readout: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("trip")
        if (path == null) { finish(); return }
        trip = Trip.load(File(path))

        val cyan = Color.parseColor("#C9F2F4"); val dim = Color.parseColor("#8FB8C8")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0A141D")); setPadding(pad(12), pad(12), pad(12), pad(12)) }

        val summary = TextView(this).apply { setTextColor(cyan); textSize = 14f; text = summaryText() }
        root.addView(summary)

        view = TripReplayView(this).apply { this.trip = this@TripReplayActivity.trip }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        readout = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(0, pad(8), 0, pad(4)) }
        root.addView(readout)

        val seek = SeekBar(this).apply { max = (trip.samples.size - 1).coerceAtLeast(0) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { view.index = p; readout.text = sampleText(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(seek)

        val share = Button(this).apply { text = "Compartilhar viagem (imagem)"; isAllCaps = false }
        share.setOnClickListener { shareImage() }
        root.addView(share)

        setContentView(root)
        if (trip.samples.isNotEmpty()) readout.text = sampleText(0)
    }

    private fun shareImage() {
        try {
            val bmp = TripImage.render(trip)
            val dir = File(cacheDir, "shared"); dir.mkdirs()
            val f = File(dir, "viagem.png")
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, summaryText())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Compartilhar viagem"))
        } catch (e: Exception) {
            // fallback texto se algo falhar
            val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, summaryText()) }
            startActivity(Intent.createChooser(i, "Compartilhar viagem"))
        }
    }

    private fun summaryText(): String {
        val d = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(trip.startMs))
        val dur = fmtDur(trip.durationMs)
        return "Viagem $d\n" +
            "Distância ${"%.1f".format(trip.distanceKm)} km  ·  $dur\n" +
            "Máx: ${trip.maxSpeed.toInt()} km/h · ${trip.maxRpm.toInt()} rpm · ${trip.maxEct.toInt()}°C\n" +
            "Combustível ${"%.1f".format(trip.fuelL)} L  ·  média ${"%.1f".format(trip.avgKmL)} km/L"
    }

    private fun sampleText(i: Int): String {
        val s = trip.samples.getOrNull(i) ?: return ""
        val rel = fmtDur(s.t - trip.startMs)
        val g = if (s.gear > 0) "M${s.gear}" else if (s.gear == 0) "N" else "-"
        return "$rel  ·  ${s.speed.toInt()} km/h  ·  ${s.rpm.toInt()} rpm  ·  $g  ·  ${s.ect.toInt()}°C  ·  ${"%.1f".format(s.economy)} km/L"
    }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    private fun pad(dp: Int) = Math.round(dp * resources.displayMetrics.density)
}
