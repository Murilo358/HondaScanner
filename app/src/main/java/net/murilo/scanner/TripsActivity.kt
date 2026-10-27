package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro do sistema: lista das sessões gravadas automaticamente. Toque abre a linha do tempo; segurar apaga. */
class TripsActivity : Activity() {
    private val CYAN = Color.parseColor("#C9F2F4"); private val DIM = Color.parseColor("#8FB8C8")

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); Theme.init(this); render() }

    private fun render() {
        val files = TripLogger.list(this).filter { it.length() > 200 }   // ignora sessões vazias (só cabeçalho)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg); setPadding(dp(16), dp(16), dp(16), dp(24)) }
        col.addView(TextView(this).apply { text = "‹  Registro"; setTextColor(CYAN); textSize = 24f; setTypeface(typeface, Typeface.BOLD); setOnClickListener { finish() } })
        col.addView(TextView(this).apply {
            text = if (TripLogger.active) "● gravando agora — ${TripLogger.samples} pontos" else "grava sozinho quando o carro conecta"
            setTextColor(if (TripLogger.active) Color.parseColor("#2DC06E") else DIM); textSize = 13f; setPadding(0, dp(2), 0, dp(12))
        })

        if (files.isEmpty()) {
            col.addView(TextView(this).apply { text = "Nenhuma sessão ainda.\nConecte o scanner (ou simule) que ele grava sozinho."; setTextColor(DIM); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) })
        } else {
            // shortcut: carrega cada CSV pra montar a lista; se ficar lento com muitas sessões, cachear uma linha de stats
            for (f in files) col.addView(sessionBlock(f))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Theme.bg); addView(col) })
    }

    private fun sessionBlock(f: File): View {
        val t = Trip.load(f)
        val dateStr = SimpleDateFormat("EEE dd/MM HH:mm", Locale("pt", "BR")).format(Date(startOf(f)))
        val dur = t.durationMs / 1000
        val durStr = if (dur >= 3600) "%d:%02d:%02d".format(dur / 3600, (dur % 3600) / 60, dur % 60) else "%d:%02d".format(dur / 60, dur % 60)

        val block = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#13202B")) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { startActivity(Intent(this@TripsActivity, DatalogActivity::class.java).putExtra("trip", f.absolutePath)) }
            setOnLongClickListener {
                AlertDialog.Builder(this@TripsActivity).setTitle("Apagar esta sessão?").setMessage("$dateStr · ${durStr}")
                    .setPositiveButton("Apagar") { _, _ -> TripLogger.delete(f); render() }
                    .setNegativeButton("Cancelar", null).show()
                true
            }
        }
        block.addView(View(this).apply { setBackgroundColor(Color.parseColor("#2DC06E")); layoutParams = LinearLayout.LayoutParams(dp(3), dp(44)) })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply { text = dateStr; setTextColor(CYAN); textSize = 15.5f; setTypeface(typeface, Typeface.BOLD) })
        texts.addView(TextView(this).apply { text = "$durStr · máx ${t.maxSpeed.toInt()} km/h"; setTextColor(DIM); textSize = 12f })
        block.addView(texts)
        block.addView(TextView(this).apply {
            text = "%.1f km".format(t.distanceKm); setTextColor(Color.parseColor("#B9E6F2")); textSize = 15f
            setPadding(dp(8), 0, dp(14), 0)
        })
        return block
    }

    private fun startOf(f: File): Long =
        f.name.removePrefix("trip_").removeSuffix(".csv").toLongOrNull() ?: f.lastModified()

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
}
