package net.murilo.scanner

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Relatório de saúde do carro: nota geral + problemas + estatísticas + combustível + DTC + manutenção. */
class ReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = Shared.dash
        val issues = ArrayList<String>()
        var score = 100

        val dtcs = d?.let { DtcReader.read(it) } ?: emptyList()
        if (dtcs.isNotEmpty()) { score -= 30; issues.add("${dtcs.size} código(s) de erro (DTC)") }
        if (d != null && d.connected) {
            if (d.ect > Feat.TEMP_WARN) { score -= 20; issues.add("Temperatura alta (${d.ect.toInt()}°C)") }
            if (d.battery in 1f..Feat.BAT_WARN) { score -= 15; issues.add("Bateria baixa (${"%.1f".format(d.battery)}V)") }
        }
        Maint.items.filter { Maint.remainingKm(it) < 0f }.forEach { score -= 8; issues.add("Manutenção vencida: ${it.name}") }
        score = score.coerceIn(0, 100)

        val scoreColor = when { score >= 80 -> 0xFF2DC06E.toInt(); score >= 50 -> 0xFFFFB300.toInt(); else -> 0xFFD44E63.toInt() }

        Theme.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg); setPadding(pad(16), pad(16), pad(16), pad(16)) }
        root.addView(TextView(this).apply { text = "‹  Relatório de saúde"; setTextColor(Color.parseColor("#C9F2F4")); textSize = 18f; setOnClickListener { finish() } })
        root.addView(TextView(this).apply { text = "$score/100"; setTextColor(scoreColor); textSize = 46f; setPadding(0, pad(6), 0, pad(10)) })

        val body = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; text = report(d, dtcs, issues) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(this).apply { text = "Manutenção"; isAllCaps = false; setOnClickListener { startActivity(Intent(this@ReportActivity, MaintActivity::class.java)) } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(Button(this).apply { text = "Compartilhar"; isAllCaps = false; setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Saúde do carro: $score/100\n\n" + report(d, dtcs, issues)) }, "Compartilhar"))
        } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btns)
        setContentView(root)
    }

    private fun report(d: Dash?, dtcs: List<DtcReader.Dtc>, issues: List<String>): String = buildString {
        append("PROBLEMAS\n")
        if (issues.isEmpty()) append("  ✓ Nenhum problema detectado\n") else issues.forEach { append("  ⚠ $it\n") }
        append("\nCÓDIGOS DE ERRO\n")
        if (dtcs.isEmpty()) append("  Nenhum\n") else dtcs.forEach { append("  DTC ${it.code} — ${it.desc}\n") }
        if (d != null && d.connected) {
            append("\nAGORA\n")
            append("  Temp: ${d.ect.toInt()}°C · Bateria: ${"%.1f".format(d.battery)}V · Lambda: ${"%.2f".format(d.lambda)}\n")
            append("  Combustível: ${d.fuelPct.toInt()}% · ${"%.1f".format(d.economyKmL)} km/L · ${d.autonomyKm.toInt()} km\n")
        }
        append("\nESTATÍSTICAS (app)\n")
        append("  Rodados: ${Stats.totalKm.toInt()} km · ${"%.1f".format(Stats.engineHours)} h de motor\n")
        append("  Máx: ${Stats.maxSpeedEver.toInt()} km/h · ${Stats.maxRpmEver.toInt()} rpm\n")
        append("  VTEC: ${Stats.vtecCount}x · redline: ${Stats.redlineHits}x\n")
        append("  Melhor 0-100: ${if (PerfMeter.best0to100 > 0) "%.2fs".format(PerfMeter.best0to100) else "—"} · pico ${PerfMeter.peakHp.toInt()} cv\n")
        append("\nMANUTENÇÃO\n")
        Maint.items.forEach {
            val r = Maint.remainingKm(it)
            append("  ${it.name}: ${if (r < 0) "VENCIDA (${(-r).toInt()} km)" else "em ${r.toInt()} km"}\n")
        }
    }

    private fun pad(dp: Int) = Math.round(dp * resources.displayMetrics.density)
}
