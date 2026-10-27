package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Diagnóstico: lê os códigos de erro (DTC) da ECU e permite limpar. */
class DtcActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private val refresher = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1500) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg); setPadding(pad(14), pad(14), pad(14), pad(14)) }
        root.addView(TextView(this).apply { text = "‹  Diagnóstico — Códigos de erro"; setTextColor(Color.parseColor("#C9F2F4")); textSize = 18f; setPadding(0, 0, 0, pad(10)); setOnClickListener { finish() } })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(this).apply { text = "Atualizar"; isAllCaps = false; setOnClickListener { refresh() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(Button(this).apply { text = "Limpar códigos"; isAllCaps = false; setOnClickListener { confirmClear() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btns)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); h.post(refresher) }
    override fun onPause() { super.onPause(); h.removeCallbacks(refresher) }

    private fun refresh() {
        content.removeAllViews()
        val dash = Shared.dash
        if (dash == null || !dash.connected) { addLine("Conecte o scanner para ler os códigos.", Color.parseColor("#8FB8C8")); return }
        if (!DtcReader.hasData(dash)) { addLine("Lendo a ECU…", Color.parseColor("#8FB8C8")); return }
        val codes = DtcReader.read(dash)
        if (codes.isEmpty()) { addLine("✓ Nenhum código de erro", Color.parseColor("#2DC06E")); return }
        for (c in codes) addLine("⚠ DTC ${c.code} — ${c.desc}", Color.parseColor("#FFB300"))
    }

    private fun addLine(t: String, col: Int) {
        content.addView(TextView(this).apply { text = t; setTextColor(col); textSize = 16f; setPadding(0, pad(6), 0, pad(6)) })
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Limpar códigos?")
            .setMessage("Isso apaga os códigos armazenados na ECU. A luz de injeção pode voltar se o problema persistir.")
            .setPositiveButton("Limpar") { _, _ ->
                Shared.client?.let { it.clearDtcRequested = true; toast("Limpando…") } ?: toast("Scanner não conectado")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    private fun pad(dp: Int) = Math.round(dp * resources.displayMetrics.density)
}
