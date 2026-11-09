package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Diagnóstico (design 6A): cada código vira um cartão com sintoma e causa comum em linguagem
 *  de oficina; limpar fica separado, com a consequência escrita. Sem códigos, vira o estado
 *  "limpo e vigiando". */
class DtcActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var headRight: android.widget.TextView
    private val refresher = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1500) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        headRight = Ui.text(this, "", 12f, Ui.MUTED)
        val head = Ui.header(this, "Diagnóstico", null) { finish() }
        head.addView(headRight)
        root.addView(head)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(16)) }
        root.addView(ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Ui.text(this, "Relendo a cada 1,5 s enquanto esta tela está aberta", 12f, Ui.FAINT).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        setContentView(root)
    }

    override fun onResume() { super.onResume(); h.post(refresher) }
    override fun onPause() { super.onPause(); h.removeCallbacks(refresher) }

    private fun refresh() {
        content.removeAllViews()
        val dash = Shared.dash
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        if (dash == null || !dash.connected) {
            headRight.text = ""
            content.addView(centerCard("Conecte o scanner", "para ler os códigos gravados na ECU"))
            return
        }
        if (!DtcReader.hasData(dash)) {
            headRight.text = ""
            content.addView(centerCard("Lendo a ECU…", "aguarde alguns segundos"))
            return
        }
        headRight.text = "lido agora, $now"
        val codes = DtcReader.read(dash)

        if (codes.isEmpty()) {
            val card = Ui.card(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(18), dp(24), dp(18), dp(24)) }
            card.addView(View(this).apply {
                background = Ui.rounded(Ui.ACCENT, dp(6).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
            })
            card.addView(Ui.text(this, "Nenhum código de erro", 16f, Ui.TEXT, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
            })
            card.addView(Ui.text(this, "ECU verificada às $now", 13f, Ui.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
            })
            content.addView(card, lp(8))
            return
        }

        // linha de estado: contagem + luz de injeção
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(4), 0, 0)
        }
        val n = codes.size
        status.addView(Ui.text(this, "$n código${if (n == 1) "" else "s"}", 15f, Ui.WARN, bold = true))
        status.addView(Ui.text(this, " gravado${if (n == 1) "" else "s"} na ECU", 15f, Ui.MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (dash.mil) status.addView(Ui.statusPill(this, "luz de injeção acesa", Ui.PILL_WARN_BG, Ui.WARN))
        content.addView(status)

        for (code in codes) content.addView(dtcCard(code), lp(10))

        // depois do reparo
        val act = Ui.card(this)
        act.addView(Ui.text(this, "Depois do reparo", 13f, Ui.TEXT, bold = true))
        act.addView(Ui.text(this, "Limpar apaga os códigos da ECU. Se o problema continuar, a luz de injeção volta a acender.", 12.5f, Ui.MUTED).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Ui.pill(this, "Ler de novo", primary = false) { refresh() }, btnLp())
        btns.addView(Ui.pill(this, "Limpar códigos", primary = false) { confirmClear() }.apply {
            setTextColor(Ui.NEG)
        }, btnLp())
        act.addView(btns)
        content.addView(act, lp(14))
    }

    private fun dtcCard(c: DtcReader.Dtc): View {
        val card = Ui.card(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(this, c.code.toString(), 26f, Ui.TEXT, bold = true).apply { setPadding(0, 0, dp(14), 0) })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(Ui.text(this, c.desc, 14f, Ui.TEXT, bold = true))
        top.addView(texts)
        top.addView(Ui.statusPill(this, "ativo", Ui.PILL_WARN_BG, Ui.WARN))
        card.addView(top)
        card.addView(Ui.text(this, DtcHints.forCode(c.code), 13f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        return card
    }

    private fun centerCard(title: String, sub: String): View {
        val card = Ui.card(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(18), dp(24), dp(18), dp(24)) }
        card.addView(Ui.text(this, title, 16f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER })
        card.addView(Ui.text(this, sub, 13f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        return card
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Limpar códigos?")
            .setMessage("Isso apaga os códigos armazenados na ECU. A luz de injeção pode voltar se o problema persistir.")
            .setPositiveButton("Limpar") { _, _ ->
                Shared.client?.let { it.clearDtcRequested = true; toast("Limpando…") } ?: toast("Scanner não conectado")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }
    private fun btnLp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        rightMargin = dp(5); leftMargin = dp(5)
    }
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = Ui.dp(this, v)
}

/** Sintoma típico + causa comum por código (Honda OBD1), em linguagem de oficina. */
object DtcHints {
    private val H = mapOf(
        0 to "Falha interna da ECU. Causa comum: solda fria ou capacitor vazado na placa.",
        1 to "Sintoma: consumo alto e mistura sem correção. Causa comum: sonda O2 velha ou chicote/aterramento ruim.",
        3 to "Sintoma: lenta instável e falha sob carga. Causa comum: mangueira do MAP solta/rachada ou o próprio sensor.",
        4 to "Sintoma: motor morre ou não pega. Causa comum: sensor CKP no distribuidor; verifique o conector.",
        5 to "Sintoma: marcha lenta irregular. Causa comum: circuito do MAP; conector e chicote primeiro.",
        6 to "Sintoma: partida a frio ruim e ventoinha na hora errada. Causa comum: sensor ECT ou chicote.",
        7 to "Sintoma: resposta irregular do acelerador. Causa comum: TPS desregulado ou gasto.",
        8 to "Sintoma: ponto errado, motor fraco. Causa comum: sensor TDC no distribuidor.",
        9 to "Sintoma: falha de sincronismo. Causa comum: sensor CYP no distribuidor.",
        10 to "Sintoma: mistura errada com ar muito quente ou frio. Causa comum: sensor IAT ou chicote.",
        12 to "Sintoma: marcha lenta irregular. Causa comum: válvula EGR suja ou travada.",
        13 to "Sintoma: correção de altitude errada. Causa comum: sensor BARO (dentro da ECU).",
        14 to "Sintoma: lenta oscilando ou morrendo ao parar. Causa comum: conector da EACV solto ou válvula suja de carvão; limpar costuma resolver.",
        15 to "Sintoma: falha de centelha. Causa comum: igniter ou distribuidor.",
        16 to "Sintoma: cilindro falhando. Causa comum: injetor ou chicote do injetor.",
        17 to "Sintoma: velocímetro do painel zerado. Causa comum: sensor de velocidade (VSS) na transmissão.",
        20 to "Sintoma: carga elétrica irregular. Causa comum: circuito ELD; verificar fusíveis.",
        21 to "Sintoma: VTEC não engata. Causa comum: solenoide do VTEC ou nível de óleo baixo.",
        22 to "Sintoma: VTEC não engata. Causa comum: pressostato do VTEC ou óleo baixo/errado.",
        23 to "Sintoma: ponto atrasado, perda de potência. Causa comum: sensor de detonação ou chicote.",
        41 to "Sintoma: o carro roda, mas gasta mais frio. Causa comum: fusível do aquecedor ou chicote da sonda. Verifique o fusível antes de trocar a sonda.",
        43 to "Sintoma: correção de mistura no limite. Causa comum: pressão de combustível (bomba/regulador) ou vazamento de vácuo."
    )
    fun forCode(code: Int) = H[code]
        ?: "Verifique conector e chicote do sensor antes de trocar a peça. Consulte a tabela de códigos OBD1 Honda."
}
