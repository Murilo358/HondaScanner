package net.murilo.scanner

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog

/** Tela de Ajustes full-screen (Combustível, Temperatura, Bateria, Motor) — seções claras + botões −/+. */
class SettingsActivity : Activity() {
    private val BG get() = Theme.bg
    private val PANEL = Color.parseColor("#0F1C27")
    private val CYAN = Color.parseColor("#C9F2F4")
    private val DIM = Color.parseColor("#8FB8C8")
    private val ACC get() = Theme.menuAccent
    private lateinit var container: LinearLayout
    private var panel: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(container)
        setContentView(scroll)
        rebuild()
    }

    private fun rebuild() {
        container.removeAllViews()
        header("Ajustes", "toque em −/+ ou no número pra digitar")

        section("Combustível", "calibração do consumo")
        actionRow("Tanque cheio", "marque ao abastecer") { FuelCalc.fillFull(); toast("Tanque cheio") }
        stepper("Capacidade", "L", { FuelCalc.tankL }, 1f, 0, 5f, 120f) { FuelCalc.setConfig(it, FuelCalc.injCc, FuelCalc.cylinders, FuelCalc.corr) }
        stepper("Vazão do bico", "cc/min", { FuelCalc.injCc }, 5f, 0, 80f, 1200f) { FuelCalc.setConfig(FuelCalc.tankL, it, FuelCalc.cylinders, FuelCalc.corr) }
        stepper("Cilindros", "", { FuelCalc.cylinders.toFloat() }, 1f, 0, 1f, 12f) { FuelCalc.setConfig(FuelCalc.tankL, FuelCalc.injCc, it.toInt(), FuelCalc.corr) }
        stepper("Fator de calibração", "ajuste fino", { FuelCalc.corr }, 0.05f, 2, 0.3f, 3f) { FuelCalc.setConfig(FuelCalc.tankL, FuelCalc.injCc, FuelCalc.cylinders, it) }
        actionRow("Zerar viagem", "reinicia o consumo") { Shared.dash?.let { FuelCalc.resetTrip(it) }; toast("Viagem zerada") }

        section("Temperatura do motor", "limites em °C")
        stepper("Motor frio abaixo de", "não forçar", { Feat.WARMUP_TEMP }, 1f, 0, 20f, 90f) { Feat.setThreshold("WARMUP_TEMP", it) }
        stepper("Motor pronto acima de", "pode acelerar", { Feat.IDEAL_TEMP }, 1f, 0, 40f, 110f) { Feat.setThreshold("IDEAL_TEMP", it) }
        stepper("Aviso", "amarelo", { Feat.TEMP_WARN }, 1f, 0, 90f, 130f) { Feat.setThreshold("TEMP_WARN", it) }
        stepper("Alerta", "vermelho", { Feat.TEMP_ALERT }, 1f, 0, 95f, 140f) { Feat.setThreshold("TEMP_ALERT", it) }

        section("Bateria", "volts")
        stepper("Aviso", "V", { Feat.BAT_WARN }, 0.1f, 1, 10f, 14f) { Feat.setThreshold("BAT_WARN", it) }
        stepper("Alerta", "V", { Feat.BAT_ALERT }, 0.1f, 1, 9f, 13f) { Feat.setThreshold("BAT_ALERT", it) }

        section("Motor", "")
        stepper("Redline (aviso)", "RPM", { Feat.REDLINE_RPM }, 100f, 0, 4000f, 9000f) { Feat.setThreshold("REDLINE_RPM", it) }
    }

    // ---------- blocos de UI (duas colunas: rótulo | painel) ----------

    private fun header(title: String, sub: String) {
        container.addView(TextView(this).apply {
            text = "‹  $title"; setTextColor(CYAN); textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { finish() }
        })
        container.addView(TextView(this).apply {
            text = sub; setTextColor(DIM); textSize = 13f; setPadding(0, dp(2), 0, dp(6))
        })
    }

    /** Abre uma seção em duas colunas e aponta o painel pros próximos steppers/ações. */
    private fun section(title: String, desc: String) {
        val sec = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        val lab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 34f)
        }
        lab.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        if (desc.isNotEmpty()) lab.addView(TextView(this).apply { text = desc; setTextColor(DIM); textSize = 12f })
        val pan = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Theme.darken(Theme.bg, 0.35f)) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 66f)
        }
        sec.addView(lab); sec.addView(pan)
        container.addView(sec); panel = pan
    }

    /** Linha de valor dentro do painel: rótulo + valor mono + −/+ (toque no valor = digitar). */
    private fun stepper(title: String, unit: String, get: () -> Float, step: Float, decimals: Int,
                        min: Float, max: Float, set: (Float) -> Unit) {
        val card = card()
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        texts.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 15f })
        if (unit.isNotEmpty()) texts.addView(TextView(this).apply { text = unit; setTextColor(DIM); textSize = 11f })
        card.addView(texts)

        val valueTv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER; minWidth = dp(56)
        }
        fun fmt(v: Float) = if (decimals == 0) v.toInt().toString() else "%.${decimals}f".format(v)
        fun render() { valueTv.text = fmt(get()) }
        fun apply(v: Float) { set(v.coerceIn(min, max)); render() }

        card.addView(roundBtn("−") { apply(get() - step) })
        card.addView(valueTv)
        card.addView(roundBtn("+") { apply(get() + step) })
        valueTv.setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(fmt(get()))
            }
            AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton("OK") { _, _ -> input.text.toString().replace(',', '.').toFloatOrNull()?.let { apply(it) } }
                .setNegativeButton("Cancelar", null).show()
        }
        render()
        (panel ?: container).addView(card)
    }

    private fun actionRow(title: String, sub: String, onClick: () -> Unit) {
        val card = card()
        card.isClickable = true; card.setOnClickListener { onClick() }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        texts.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 15f })
        if (sub.isNotEmpty()) texts.addView(TextView(this).apply { text = sub; setTextColor(DIM); textSize = 11f })
        card.addView(texts)
        card.addView(TextView(this).apply { text = "›"; setTextColor(ACC); textSize = 24f; setPadding(dp(8), 0, dp(4), 0) })
        (panel ?: container).addView(card)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun roundBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 22f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#12283A")); setStroke(dp(1), ACC) }
        val s = dp(46)
        layoutParams = LinearLayout.LayoutParams(s, s).apply { leftMargin = dp(6); rightMargin = dp(6) }
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
}
