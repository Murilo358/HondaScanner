package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Ajustes (design 10A): "Tanque cheio" (o gesto mais frequente) no topo como única ação primária;
 *  steppers de −/+ com os limites escritos sob o rótulo; toque no número para digitar. */
class SettingsActivity : Activity() {
    private lateinit var container: LinearLayout
    private var panel: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(14), dp(16), 0)
        }
        root.addView(Ui.header(this, "Ajustes", "toque no número para digitar") { finish() })
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(24)) }
        root.addView(ScrollView(this).apply { addView(container); isFillViewport = true; isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        rebuild()
    }

    private fun rebuild() {
        container.removeAllViews()
        panel = null

        // gesto mais frequente: tanque cheio
        val fuelCard = Ui.card(this, vertical = false)
        val ftexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ftexts.addView(Ui.text(this, "Acabei de abastecer", 15f, Ui.TEXT, bold = true))
        ftexts.addView(Ui.text(this, "marca o tanque como cheio (${FuelCalc.tankL.toInt()} L)", 12f, Ui.MUTED))
        fuelCard.addView(ftexts)
        fuelCard.addView(Ui.pill(this, "Tanque cheio", primary = true) { FuelCalc.fillFull(); toast("Tanque cheio"); rebuild() })
        container.addView(fuelCard, lp(2))

        section("Combustível", "calibração do consumo")
        stepper("Capacidade", "L", { FuelCalc.tankL }, 1f, 0, 5f, 120f) { FuelCalc.setConfig(it, FuelCalc.injCc, FuelCalc.cylinders, FuelCalc.corr) }
        stepper("Vazão do bico", "cc/min", { FuelCalc.injCc }, 5f, 0, 80f, 1200f) { FuelCalc.setConfig(FuelCalc.tankL, it, FuelCalc.cylinders, FuelCalc.corr) }
        stepper("Cilindros", "", { FuelCalc.cylinders.toFloat() }, 1f, 0, 1f, 12f) { FuelCalc.setConfig(FuelCalc.tankL, FuelCalc.injCc, it.toInt(), FuelCalc.corr) }
        stepper("Fator de calibração", "ajuste fino", { FuelCalc.corr }, 0.05f, 2, 0.3f, 3f) { FuelCalc.setConfig(FuelCalc.tankL, FuelCalc.injCc, FuelCalc.cylinders, it) }
        actionRow("Zerar viagem", "reinicia o consumo") { Shared.dash?.let { FuelCalc.resetTrip(it) }; toast("Viagem zerada") }

        section("Temperatura do motor", "°C — aviso pinta o painel de amarelo, alerta de vermelho")
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

    /** Título da seção + novo painel (cartão) que recebe as próximas linhas. */
    private fun section(title: String, desc: String) {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(18), 0, dp(6))
        }
        head.addView(Ui.text(this, title, 15f, Ui.TEXT, bold = true))
        if (desc.isNotEmpty()) head.addView(Ui.text(this, desc, 12f, Ui.MUTED))
        container.addView(head)
        val pan = Ui.card(this).apply { setPadding(dp(18), dp(2), dp(14), dp(2)) }
        container.addView(pan)
        panel = pan
    }

    /** Linha de valor: rótulo + limites, botões −/+ redondos de 44dp, toque no valor digita. */
    private fun stepper(title: String, hint: String, get: () -> Float, step: Float, decimals: Int,
                        min: Float, max: Float, set: (Float) -> Unit) {
        fun fmt(v: Float) = if (decimals == 0) v.toInt().toString() else "%.${decimals}f".format(v)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(Ui.text(this, title, 14f, Ui.TEXT))
        val limits = buildString {
            append(fmt(min)); append("–"); append(fmt(max))
            if (hint.isNotEmpty()) append(" "); append(hint)
        }
        texts.addView(Ui.text(this, limits, 11f, Ui.FAINT))
        row.addView(texts)

        val valueTv = Ui.text(this, fmt(get()), 17f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER; minWidth = dp(64)
        }
        fun apply(v: Float) { set(v.coerceIn(min, max)); valueTv.text = fmt(get()) }
        row.addView(roundBtn("−") { apply(get() - step) })
        row.addView(valueTv)
        row.addView(roundBtn("+") { apply(get() + step) })
        valueTv.setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(fmt(get()))
            }
            AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton("OK") { _, _ -> input.text.toString().replace(',', '.').toFloatOrNull()?.let { apply(it) } }
                .setNegativeButton("Cancelar", null).show()
        }
        (panel ?: container).addView(row)
    }

    private fun actionRow(title: String, sub: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
            isClickable = true; setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(Ui.text(this, title, 14f, Ui.TEXT))
        if (sub.isNotEmpty()) texts.addView(Ui.text(this, sub, 11f, Ui.FAINT))
        row.addView(texts)
        row.addView(Ui.text(this, "›", 20f, Ui.MUTED).apply { setPadding(dp(8), 0, dp(6), 0) })
        (panel ?: container).addView(row)
    }

    private fun roundBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = Ui.rounded(Ui.RAISED, dp(22).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(6); rightMargin = dp(6) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = Ui.dp(this, v)
}
