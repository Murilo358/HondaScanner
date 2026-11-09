package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/** Manutenção (design 9A): itens ordenados por urgência; o vencido abre a tela com "Feito hoje"
 *  no próprio cartão e a barra mostra o intervalo consumido (excedente em vermelho). */
class MaintActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); Theme.init(this); render() }

    private fun render() {
        val c = this
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        root.addView(Ui.header(c, "Manutenção", "${Stats.totalKm.toInt()} km no app") { finish() })
        root.addView(Ui.text(c, "toque num item para marcar feito ou mudar o intervalo", 12f, Ui.FAINT).apply {
            setPadding(dp(2), dp(2), 0, 0)
        })

        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(16)) }
        for (item in Maint.items.sortedBy { Maint.remainingKm(it) }) col.addView(itemCard(item), lp())

        col.addView(Ui.pill(c, "+ Cadastrar serviço", primary = false) { addDialog() }, lp())

        col.addView(Ui.text(c, "O hodômetro conta só o que o app viu conectado. Se o carro rodou sem o scanner, marque o serviço feito com a data real na oficina.", 12f, Ui.FAINT).apply {
            setPadding(dp(2), dp(14), dp(2), 0)
        })
        root.addView(ScrollView(c).apply { addView(col); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun itemCard(item: Maint.Item): View {
        val c = this
        val r = Maint.remainingKm(item)
        val overdue = r < 0f
        val elapsed = item.intervalKm - r                       // km rodados desde o serviço
        val usedFrac = (elapsed / item.intervalKm).coerceIn(0f, 1f)
        val near = !overdue && r < item.intervalKm * 0.2f

        val card = Ui.card(c).apply {
            isClickable = true; setOnClickListener { itemMenu(item) }
        }
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(Ui.text(c, item.name, 15f, Ui.TEXT, bold = true))
        if (overdue) texts.addView(Ui.text(c, "vencida há ${(-r).toInt()} km", 12f, Ui.NEG))
        top.addView(texts)
        if (overdue) {
            top.addView(Ui.pill(c, "Feito hoje", primary = true) {
                AlertDialog.Builder(c).setTitle("Marcar como feito?")
                    .setMessage("${item.name} feita hoje, a ${Stats.totalKm.toInt()} km do app.")
                    .setPositiveButton("Feito") { _, _ -> Maint.markDone(item); render() }
                    .setNegativeButton("Cancelar", null).show()
            })
        } else {
            top.addView(Ui.text(c, "em ${r.toInt()} km", 15f, if (near) Ui.WARN else Ui.TEXT, bold = true).apply {
                setPadding(0, 0, dp(12), 0)
            })
            top.addView(Ui.pill(c, "Feito", primary = false) {
                AlertDialog.Builder(c).setTitle("Marcar como feito?")
                    .setMessage("${item.name} feita hoje, a ${Stats.totalKm.toInt()} km do app.")
                    .setPositiveButton("Feito") { _, _ -> Maint.markDone(item); render() }
                    .setNegativeButton("Cancelar", null).show()
            }.apply { textSize = 12.5f; minHeight = dp(32); setPadding(dp(14), dp(5), dp(14), dp(5)) })
        }
        card.addView(top)

        // barra: intervalo consumido; vencido passa do fim do trilho em vermelho
        if (overdue) {
            val ovFrac = ((-r) / elapsed).coerceIn(0.05f, 0.3f)
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            val hpx = dp(6)
            row.addView(View(c).apply { background = Ui.rounded(0xFF6B6B6B.toInt(), hpx / 2f) },
                LinearLayout.LayoutParams(0, hpx, 1f - ovFrac))
            row.addView(View(c).apply { background = Ui.rounded(Ui.NEG, hpx / 2f) },
                LinearLayout.LayoutParams(0, hpx, ovFrac))
            card.addView(row)
        } else {
            card.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0)
                addView(Ui.bar(c, usedFrac, if (near) Ui.WARN else 0xFF6B6B6B.toInt()))
            })
        }
        val sub = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        sub.addView(Ui.text(c, if (overdue) "feito a ${item.lastKm.toInt()} km" else "intervalo ${item.intervalKm.toInt()} km", 11f, Ui.FAINT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        sub.addView(Ui.text(c, if (overdue) "intervalo ${item.intervalKm.toInt()} km" else "${(usedFrac * 100).toInt()} % usado", 11f, Ui.FAINT))
        card.addView(sub)
        return card
    }

    private fun itemMenu(item: Maint.Item) {
        val opts = arrayListOf("Marcar como feito agora", "Mudar intervalo")
        if (item.custom) opts.add("Apagar este serviço")
        AlertDialog.Builder(this).setTitle(item.name)
            .setItems(opts.toTypedArray()) { _, i ->
                when (i) {
                    0 -> { Maint.markDone(item); render() }
                    1 -> {
                        val inp = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(item.intervalKm.toInt().toString()) }
                        AlertDialog.Builder(this).setTitle("Intervalo (km)").setView(inp)
                            .setPositiveButton("OK") { _, _ -> inp.text.toString().toFloatOrNull()?.let { Maint.setInterval(item, it); render() } }
                            .setNegativeButton("Cancelar", null).show()
                    }
                    2 -> {
                        AlertDialog.Builder(this).setTitle("Apagar ${item.name}?")
                            .setPositiveButton("Apagar") { _, _ -> Maint.removeCustom(item); render() }
                            .setNegativeButton("Cancelar", null).show()
                    }
                }
            }.setNegativeButton("Fechar", null).show()
    }

    /** Cadastra um serviço novo: nome + intervalo em km. Começa contado do hodômetro atual. */
    private fun addDialog() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
        }
        val nome = EditText(this).apply { hint = "Nome (ex.: Fluido de freio)" }
        val intervalo = EditText(this).apply { hint = "Intervalo em km (ex.: 20000)"; inputType = InputType.TYPE_CLASS_NUMBER }
        col.addView(nome); col.addView(intervalo)
        AlertDialog.Builder(this).setTitle("Cadastrar serviço").setView(col)
            .setPositiveButton("Cadastrar") { _, _ ->
                val n = nome.text.toString().trim()
                val km = intervalo.text.toString().toFloatOrNull()
                if (n.isNotEmpty() && km != null && km > 0f) { Maint.addCustom(n, km); render() }
                else android.widget.Toast.makeText(this, "Preencha nome e intervalo", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }
    private fun dp(v: Int) = Ui.dp(this, v)
}
