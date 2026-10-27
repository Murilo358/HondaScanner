package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Tela de cores: paleta de presets + ajuste custom (R/G/B) por elemento do painel. */
object ColorSettings {
    private val ELEMENTS = listOf(
        "bg" to "Fundo",
        "ring" to "Anel dos gauges",
        "progress" to "Preenchimento (segue ponteiro)",
        "needle" to "Ponteiro",
        "redline" to "Zona vermelha (limite)",
        "text" to "Números / texto",
        "griffin" to "Grifo (tint)",
        "shiftColor" to "Shift light (cor)"
    )
    private val PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFB0BEC5.toInt(), 0xFF8D9196.toInt(), 0xFF000000.toInt(),
        0xFF0A141D.toInt(), 0xFF2B82D8.toInt(), 0xFF00B0FF.toInt(), 0xFF00E5FF.toInt(),
        0xFF00E676.toInt(), 0xFFFFD600.toInt(), 0xFFFF9100.toInt(), 0xFFE23C3C.toInt(),
        0xFFFF4081.toInt(), 0xFF7C4DFF.toInt(), 0xFFEAF4FF.toInt(), 0xFFC9F2F4.toInt()
    )

    private fun dp(c: Context, v: Int) = Math.round(v * c.resources.displayMetrics.density)

    fun showColorsMenu(activity: Activity, onChanged: () -> Unit) {
        val items = ELEMENTS.map { it.second }.toMutableList().apply { add("Restaurar padrão") }
        AlertDialog.Builder(activity)
            .setTitle("Cores do painel")
            .setItems(items.toTypedArray()) { _, i ->
                if (i < ELEMENTS.size) {
                    val (key, label) = ELEMENTS[i]
                    pickColor(activity, label, Theme.get(key)) { c ->
                        Theme.set(key, c); onChanged()
                        showColorsMenu(activity, onChanged)
                    }
                } else { Theme.reset(); onChanged() }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    fun showLayouts(activity: Activity, onChanged: () -> Unit) {
        val items = Layout.labels.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Layout do painel")
            .setSingleChoiceItems(items, Layout.selected) { dlg, i -> Layout.set(i); onChanged(); dlg.dismiss() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    fun showBackgrounds(activity: Activity, onChanged: () -> Unit) {
        val items = BgTex.labels.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Fundo do painel (segue a cor)")
            .setSingleChoiceItems(items, BgTex.selected) { dlg, i -> BgTex.set(i); onChanged(); dlg.dismiss() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    fun showElements(activity: Activity, onChanged: () -> Unit) {
        val keys = Feat.labels.keys.toList()
        val items = Feat.labels.values.toTypedArray()
        val checked = BooleanArray(keys.size) { Feat.on(keys[it]) }
        AlertDialog.Builder(activity)
            .setTitle("Elementos do painel")
            .setMultiChoiceItems(items, checked) { _, i, v -> Feat.toggle(keys[i], v); onChanged() }
            .setPositiveButton("OK", null)
            .show()
    }

    fun showShiftSettings(activity: Activity, onChanged: () -> Unit) {
        val ctx: Context = activity
        val pad = dp(ctx, 16)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val chk = CheckBox(ctx).apply { text = "Ativar shift light"; isChecked = Theme.shiftEnabled }
        root.addView(chk)
        var rpm = Theme.shiftRpm
        val lbl = TextView(ctx).apply { setPadding(0, dp(ctx, 14), 0, dp(ctx, 4)) }
        fun updLbl() { lbl.text = "RPM de troca: $rpm" }
        updLbl()
        root.addView(lbl)
        root.addView(SeekBar(ctx).apply {
            max = (8000 - 3000) / 100
            progress = ((Theme.shiftRpm - 3000) / 100).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { rpm = 3000 + p * 100; updLbl() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        AlertDialog.Builder(ctx)
            .setTitle("Shift light")
            .setView(root)
            .setPositiveButton("OK") { _, _ -> Theme.setShift(chk.isChecked, rpm); onChanged() }
            .setNeutralButton("Cor") { _, _ ->
                pickColor(ctx, "Shift light (cor)", Theme.shiftColor) { c ->
                    Theme.set("shiftColor", c); onChanged(); showShiftSettings(activity, onChanged)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickColor(ctx: Context, title: String, initial: Int, onPick: (Int) -> Unit) {
        var color = initial or (0xFF shl 24)
        val pad = dp(ctx, 16)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }

        val preview = View(ctx).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(-1, dp(ctx, 46))
        }
        root.addView(preview)

        val seeks = arrayOfNulls<SeekBar>(3)
        fun apply() {
            color = Color.rgb(seeks[0]!!.progress, seeks[1]!!.progress, seeks[2]!!.progress)
            preview.setBackgroundColor(color)
        }
        val chNames = arrayOf("R", "G", "B")
        val chVals = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
        for (k in 0..2) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 6), 0, 0)
            }
            val lbl = TextView(ctx).apply { text = chNames[k]; width = dp(ctx, 22) }
            val sb = SeekBar(ctx).apply {
                max = 255; progress = chVals[k]
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = apply()
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            seeks[k] = sb
            row.addView(lbl); row.addView(sb)
            root.addView(row)
        }

        val grid = GridLayout(ctx).apply { columnCount = 8; setPadding(0, dp(ctx, 12), 0, dp(ctx, 8)) }
        for (pc in PRESETS) {
            val sw = View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(pc); cornerRadius = dp(ctx, 4).toFloat(); setStroke(dp(ctx, 1), 0x66FFFFFF)
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(ctx, 30); height = dp(ctx, 30)
                    setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3))
                }
                setOnClickListener {
                    seeks[0]!!.progress = Color.red(pc)
                    seeks[1]!!.progress = Color.green(pc)
                    seeks[2]!!.progress = Color.blue(pc)
                    apply()
                }
            }
            grid.addView(sw)
        }
        root.addView(grid)

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("OK") { _, _ -> onPick(color) }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
