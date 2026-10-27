package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Desempenho: 0-100, 0-60, dyno virtual (potência estimada). Ao vivo. */
class PerfActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var big: TextView
    private lateinit var info: TextView
    private val tick = object : Runnable { override fun run() { update(); h.postDelayed(this, 150) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg); setPadding(pad(16), pad(16), pad(16), pad(16)) }
        root.addView(TextView(this).apply { text = "‹  Desempenho"; setTextColor(Color.parseColor("#C9F2F4")); textSize = 18f; setOnClickListener { finish() } })
        big = TextView(this).apply { setTextColor(Color.WHITE); textSize = 40f; setPadding(0, pad(10), 0, pad(10)) }
        root.addView(big)
        info = TextView(this).apply { setTextColor(Color.parseColor("#C9F2F4")); textSize = 17f }
        root.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(this).apply { text = "Zerar recordes"; isAllCaps = false; setOnClickListener { PerfMeter.resetBest(); update() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(Button(this).apply { text = "Massa do carro"; isAllCaps = false; setOnClickListener { massDialog() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btns)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun update() {
        val d = Shared.dash
        val run = if (PerfMeter.runMs > 0L) "%.2fs".format(PerfMeter.runMs / 1000f) else "—"
        big.text = "${d?.speed?.toInt() ?: 0} km/h   ·   ${PerfMeter.liveHp.toInt()} cv"
        info.text = buildString {
            append("Run atual: $run\n\n")
            append("Melhor 0-100: ${fmt(PerfMeter.best0to100)}\n")
            append("Melhor 0-60:  ${fmt(PerfMeter.best0to60)}\n")
            append("Pico estimado: ${PerfMeter.peakHp.toInt()} cv\n\n")
            append("Pare o carro (0 km/h) e acelere forte pra medir.\nMassa usada: ${PerfMeter.massKg.toInt()} kg (ajuste p/ precisão).")
        }
    }

    private fun fmt(t: Float) = if (t > 0f) "%.2fs".format(t) else "—"

    private fun massDialog() {
        val inp = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(PerfMeter.massKg.toInt().toString()) }
        AlertDialog.Builder(this).setTitle("Massa do carro (kg)").setView(inp)
            .setPositiveButton("OK") { _, _ -> inp.text.toString().toFloatOrNull()?.let { PerfMeter.setMass(it); update() } }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun pad(dp: Int) = Math.round(dp * resources.displayMetrics.density)
}
