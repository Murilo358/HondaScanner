package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Lembretes de manutenção: ver o que está por vencer, marcar como feito, mudar o intervalo. */
class MaintActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); Theme.init(this); render() }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg); setPadding(pad(16), pad(16), pad(16), pad(16)) }
        root.addView(TextView(this).apply { text = "‹  Manutenção  (hodômetro: ${Stats.totalKm.toInt()} km)"; setTextColor(Color.parseColor("#C9F2F4")); textSize = 17f; setPadding(0, 0, 0, pad(10)); setOnClickListener { finish() } })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (item in Maint.items) {
            val r = Maint.remainingKm(item)
            val col2 = if (r < 0) 0xFFD44E63.toInt() else if (r < item.intervalKm * 0.1f) 0xFFFFB300.toInt() else Color.WHITE
            val status = if (r < 0) "VENCIDA (${(-r).toInt()} km)" else "em ${r.toInt()} km"
            val row = TextView(this).apply {
                text = "${item.name}\n  $status  ·  intervalo ${item.intervalKm.toInt()} km"
                setTextColor(col2); textSize = 15f; setPadding(0, pad(8), 0, pad(8))
                setOnClickListener { itemMenu(item) }
            }
            col.addView(row)
        }
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun itemMenu(item: Maint.Item) {
        AlertDialog.Builder(this).setTitle(item.name)
            .setItems(arrayOf("Marcar como feito agora", "Mudar intervalo")) { _, i ->
                when (i) {
                    0 -> { Maint.markDone(item); render() }
                    1 -> {
                        val inp = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(item.intervalKm.toInt().toString()) }
                        AlertDialog.Builder(this).setTitle("Intervalo (km)").setView(inp)
                            .setPositiveButton("OK") { _, _ -> inp.text.toString().toFloatOrNull()?.let { Maint.setInterval(item, it); render() } }
                            .setNegativeButton("Cancelar", null).show()
                    }
                }
            }.setNegativeButton("Fechar", null).show()
    }

    private fun pad(dp: Int) = Math.round(dp * resources.displayMetrics.density)
}
