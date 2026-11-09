package net.murilo.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Relatório de saúde (design 8A): anel com a nota, frase que a traduz, fila "resolver primeiro"
 *  com o desconto de cada problema e o atalho para a tela que resolve, sinais vitais numa régua. */
class ReportActivity : Activity() {

    private class Issue(val title: String, val sub: String, val subColor: Int,
                        val action: String?, val go: (() -> Unit)?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        val c = this
        val d = Shared.dash
        val dtcs = d?.let { DtcReader.read(it) } ?: emptyList()

        // mesma régua de pontos de sempre
        var score = 100
        val issues = ArrayList<Issue>()
        if (dtcs.isNotEmpty()) {
            score -= 30
            val codes = dtcs.joinToString(" e ") { it.code.toString() }
            issues.add(Issue("${dtcs.size} código${if (dtcs.size == 1) "" else "s"} na ECU",
                "DTC $codes · −30 na nota", Ui.WARN, "Diagnóstico") {
                startActivity(Intent(c, DtcActivity::class.java))
            })
        }
        if (d != null && d.connected) {
            if (d.ect > Feat.TEMP_WARN) {
                score -= 20
                issues.add(Issue("Temperatura alta", "${d.ect.toInt()} °C agora · −20 na nota", Ui.NEG, null, null))
            }
            if (d.battery in 1f..Feat.BAT_WARN) {
                score -= 15
                issues.add(Issue("Bateria baixa", "%.1f V agora · −15 na nota".format(d.battery), Ui.NEG, null, null))
            }
        }
        val overdue = Maint.items.filter { Maint.remainingKm(it) < 0f }
        for (it in overdue) {
            score -= 8
            issues.add(Issue("${it.name} vencida",
                "há ${(-Maint.remainingKm(it)).toInt()} km · −8 na nota", Ui.NEG, "Manutenção") {
                startActivity(Intent(c, MaintActivity::class.java))
            })
        }
        score = score.coerceIn(0, 100)
        val scoreColor = when { score >= 80 -> Ui.ACCENT; score >= 50 -> Ui.WARN; else -> Ui.NEG }

        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        root.addView(Ui.header(c, "Saúde do carro",
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) { finish() })

        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(16)) }

        // anel + frase
        val lead = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lead.addView(RingView(c, score, scoreColor), LinearLayout.LayoutParams(dp(124), dp(124)))
        val phraseCol = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(20) }
        }
        phraseCol.addView(Ui.text(c, when {
            issues.isEmpty() -> "Nenhum problema detectado"
            else -> "${issues.size} ponto${if (issues.size == 1) "" else "s"} ${if (issues.size == 1) "pede" else "pedem"} atenção"
        }, 15f, Ui.TEXT, bold = true))
        phraseCol.addView(Ui.text(c, when {
            score >= 80 -> "Tudo certo para rodar."
            score >= 50 -> "Nada impede de rodar hoje. A nota volta a 100 resolvendo a lista abaixo."
            else -> "Resolva os itens abaixo antes de rodar forte."
        }, 13f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        lead.addView(phraseCol)
        col.addView(lead)

        // resolver primeiro
        if (issues.isNotEmpty()) {
            col.addView(Ui.text(c, "Resolver primeiro", 15f, Ui.TEXT, bold = true).apply {
                setPadding(dp(2), dp(20), 0, dp(2))
            })
            issues.forEachIndexed { i, issue ->
                val card = Ui.card(c, vertical = false)
                card.addView(Ui.text(c, "${i + 1}", 15f, Ui.FAINT).apply { setPadding(0, 0, dp(14), 0) })
                val texts = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                texts.addView(Ui.text(c, issue.title, 14f, Ui.TEXT, bold = true))
                texts.addView(Ui.text(c, issue.sub, 12f, issue.subColor))
                card.addView(texts)
                if (issue.action != null && issue.go != null)
                    card.addView(Ui.pill(c, issue.action, primary = false) { issue.go.invoke() })
                col.addView(card, lp(8))
            }
        }

        // agora
        col.addView(Ui.text(c, "Agora", 15f, Ui.TEXT, bold = true).apply { setPadding(dp(2), dp(20), 0, dp(2)) })
        val strip = Ui.card(c, vertical = false).apply { setPadding(0, dp(12), 0, dp(12)) }
        val on = d != null && d.connected
        strip.addView(stripCell(if (on) "${d!!.ect.toInt()}°" else "—", "água"))
        strip.addView(stripCell(if (on) "%.1f V".format(d!!.battery) else "—", "bateria"))
        strip.addView(stripCell(if (on) "%.2f".format(d!!.lambda) else "—", "lambda"))
        strip.addView(stripCell(if (on) "${d!!.fuelPct.toInt()} %" else "—", "tanque"))
        col.addView(strip, lp(8))
        if (!on) col.addView(Ui.text(c, "Conecte o scanner para ver os sinais vitais ao vivo.", 12f, Ui.FAINT).apply {
            setPadding(dp(2), dp(6), 0, 0)
        })

        root.addView(ScrollView(c).apply { addView(col); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(Ui.pill(c, "Compartilhar relatório", primary = true) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Saúde do carro: $score/100\n\n" + shareText(d, dtcs, issues))
            }, "Compartilhar"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        setContentView(root)
    }

    private fun stripCell(value: String, label: String): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        cell.addView(Ui.text(this, value, 17f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER })
        cell.addView(Ui.text(this, label, 11f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        return cell
    }

    private fun shareText(d: Dash?, dtcs: List<DtcReader.Dtc>, issues: List<Issue>): String = buildString {
        append("PROBLEMAS\n")
        if (issues.isEmpty()) append("  Nenhum problema detectado\n")
        else issues.forEach { append("  ${it.title} (${it.sub})\n") }
        append("\nCÓDIGOS DE ERRO\n")
        if (dtcs.isEmpty()) append("  Nenhum\n") else dtcs.forEach { append("  DTC ${it.code} ${it.desc}\n") }
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

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }
    private fun dp(v: Int) = Ui.dp(this, v)

    /** Anel da nota: trilho escuro + arco na cor da nota + número no centro. */
    private class RingView(ctx: Context, val score: Int, val color: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val sw = w * 0.075f
            val r = (minOf(w, h) - sw) / 2f - 2f
            val rect = RectF(w / 2f - r, h / 2f - r, w / 2f + r, h / 2f + r)
            p.strokeWidth = sw
            p.color = Ui.RAISED
            c.drawArc(rect, 0f, 360f, false, p)
            p.color = color
            c.drawArc(rect, -90f, 360f * score / 100f, false, p)
            t.color = Ui.TEXT; t.textSize = w * 0.27f
            c.drawText(score.toString(), w / 2f, h / 2f + w * 0.08f, t)
            t.color = Ui.MUTED; t.textSize = w * 0.095f
            c.drawText("de 100", w / 2f, h / 2f + w * 0.22f, t)
        }
    }
}
