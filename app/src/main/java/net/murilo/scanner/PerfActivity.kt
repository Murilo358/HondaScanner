package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Desempenho: estado "pronto" com a instrução na tela, velocidade ao vivo grande,
 *  recordes com data, massa do carro, zerar pequeno e longe. */
class PerfActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var big: TextView
    private lateinit var state: TextView
    private lateinit var c60: TextView
    private lateinit var c100: TextView
    private lateinit var cHp: TextView
    private lateinit var r100: TextView; private lateinit var r100d: TextView
    private lateinit var r60: TextView; private lateinit var r60d: TextView
    private lateinit var rHp: TextView
    private lateinit var massTv: TextView
    private val tick = object : Runnable { override fun run() { update(); h.postDelayed(this, 150) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        val c = this
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        root.addView(Ui.header(c, "Desempenho", null) { finish() })

        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(16)) }

        // cartão do run
        val run = Ui.card(c).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(18), dp(20), dp(18), dp(18)) }
        run.addView(Ui.text(c, "Pare o carro e acelere forte: o cronômetro dispara sozinho", 13f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
        })
        big = Ui.text(c, "0", 64f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        run.addView(big)
        state = Ui.text(c, "km/h · pronto", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        run.addView(state)
        val cells = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0)
        }
        c60 = cellValue(); c100 = cellValue(); cHp = cellValue()
        cells.addView(cell(c60, "0-60")); cells.addView(cell(c100, "0-100")); cells.addView(cell(cHp, "cv agora"))
        run.addView(cells)
        col.addView(run, lp(0))

        // recordes
        col.addView(Ui.text(c, "Recordes", 15f, Ui.TEXT, bold = true).apply { setPadding(dp(2), dp(18), 0, dp(6)) })
        val rec = Ui.card(c).apply { setPadding(dp(18), dp(2), dp(18), dp(2)) }
        r100 = Ui.text(c, "—", 17f, Ui.TEXT, bold = true); r100d = Ui.text(c, "", 12f, Ui.FAINT)
        r60 = Ui.text(c, "—", 17f, Ui.TEXT, bold = true); r60d = Ui.text(c, "", 12f, Ui.FAINT)
        rHp = Ui.text(c, "—", 17f, Ui.TEXT, bold = true)
        rec.addView(recRow("0-100 km/h", r100, r100d, rule = true))
        rec.addView(recRow("0-60 km/h", r60, r60d, rule = true))
        rec.addView(recRow("Pico de potência", rHp, Ui.text(c, "estimado", 12f, Ui.FAINT), rule = false))
        col.addView(rec, lp(0))

        // massa
        val mass = Ui.card(c, vertical = false).apply {
            isClickable = true; setOnClickListener { massDialog() }
        }
        val mtexts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        mtexts.addView(Ui.text(c, "Massa do carro", 14f, Ui.TEXT))
        mtexts.addView(Ui.text(c, "usada no cálculo de potência", 12f, Ui.MUTED))
        mass.addView(mtexts)
        massTv = Ui.text(c, "", 16f, Ui.TEXT, bold = true)
        mass.addView(massTv)
        mass.addView(Ui.text(c, "  ›", 18f, Ui.MUTED))
        col.addView(mass, lp(14))

        root.addView(ScrollView(c).apply { addView(col); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(Ui.text(c, "Zerar recordes", 13f, Ui.NEG).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6))
            setOnClickListener {
                AlertDialog.Builder(c).setTitle("Zerar recordes?")
                    .setMessage("Apaga o melhor 0-100, 0-60 e o pico de potência.")
                    .setPositiveButton("Zerar") { _, _ -> PerfMeter.resetBest(); update() }
                    .setNegativeButton("Cancelar", null).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun update() {
        val d = Shared.dash
        val v = d?.speed?.toInt() ?: 0
        big.text = v.toString()
        state.text = when {
            PerfMeter.runMs > 0L -> "km/h · medindo, %.2f s".format(PerfMeter.runMs / 1000f)
            v < 2 -> "km/h · pronto"
            else -> "km/h"
        }
        c60.text = if (PerfMeter.run0to60 > 0f) "%.2f".format(PerfMeter.run0to60) else "—"
        c100.text = if (PerfMeter.runMs > 0L) "%.2f".format(PerfMeter.runMs / 1000f) else "—"
        cHp.text = PerfMeter.liveHp.toInt().toString()
        r100.text = fmt(PerfMeter.best0to100); r100d.text = dateOf(PerfMeter.best100At)
        r60.text = fmt(PerfMeter.best0to60); r60d.text = dateOf(PerfMeter.best60At)
        rHp.text = if (PerfMeter.peakHp > 0f) "${PerfMeter.peakHp.toInt()} cv" else "—"
        massTv.text = "${PerfMeter.massKg.toInt()} kg"
    }

    private fun fmt(t: Float) = if (t > 0f) "%.2f s".format(t) else "—"
    private fun dateOf(ms: Long) = if (ms > 0L)
        SimpleDateFormat("EEE dd/MM", Locale("pt", "BR")).format(Date(ms)) else ""

    private fun cellValue() = Ui.text(this, "—", 20f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
    private fun cell(value: TextView, label: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        c.addView(value)
        c.addView(Ui.text(this, label, 11f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        return c
    }

    private fun recRow(label: String, value: TextView, right: TextView, rule: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        row.addView(Ui.text(this, label, 14f, Ui.TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(value)
        right.apply { minWidth = dp(76); gravity = Gravity.END }
        row.addView(right)
        return row
    }

    private fun massDialog() {
        val inp = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(PerfMeter.massKg.toInt().toString()) }
        AlertDialog.Builder(this).setTitle("Massa do carro (kg)").setView(inp)
            .setPositiveButton("OK") { _, _ -> inp.text.toString().toFloatOrNull()?.let { PerfMeter.setMass(it); update() } }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(if (top == 0) 8 else top) }
    private fun dp(v: Int) = Ui.dp(this, v)
}
