package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Nomes dados pelo usuário às sessões (como o Hondash: a gravação tem nome renomeável). */
object TripNames {
    private const val PREF = "trip_names"
    fun get(ctx: Context, f: File): String? =
        try { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(f.name, null) } catch (_: Exception) { null }
    fun set(ctx: Context, f: File, name: String?) {
        try {
            val e = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            if (name.isNullOrBlank()) e.remove(f.name) else e.putString(f.name, name.trim())
            e.apply()
        } catch (_: Exception) {}
    }
}

/** Registro do sistema (design 4A, ações como no Hondash): sessão em andamento primeiro, cartões
 *  agrupados por dia com nome renomeável, e ações explícitas por sessão (abrir, renomear,
 *  compartilhar, apagar). Total da semana no rodapé. */
class TripsActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable { override fun run() { render(); h.postDelayed(this, 2000) } }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); Theme.init(this); render() }
    override fun onResume() { super.onResume(); h.post(refresher) }
    override fun onPause() { super.onPause(); h.removeCallbacks(refresher) }

    private fun render() {
        val c = this
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        root.addView(Ui.header(c, "Registro", "grava sozinho") { finish() })

        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(16)) }

        // sessão em andamento (viva)
        if (TripLogger.active) {
            val startMs = TripLogger.currentFile?.let { startOf(it) } ?: System.currentTimeMillis()
            val el = (System.currentTimeMillis() - startMs) / 1000
            val live = Ui.card(c, vertical = false)
            live.addView(View(c).apply {
                background = Ui.rounded(Ui.ACCENT, dp(5).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(12) }
            })
            val texts = vcol()
            texts.addView(Ui.text(c, "Gravando agora", 14f, Ui.TEXT, bold = true))
            texts.addView(Ui.text(c, "começou ${hhmm(startMs)} · ${TripLogger.samples} pontos", 12f, Ui.MUTED))
            live.addView(texts)
            live.addView(Ui.text(c, "%d:%02d".format(el / 60, el % 60), 17f, Ui.TEXT, bold = true))
            col.addView(live, cardLp())
        }

        val files = TripLogger.list(c).filter { it.length() > 200 }
        if (files.isEmpty() && !TripLogger.active) {
            col.addView(Ui.text(c, "Nenhuma sessão ainda.\nConecte o scanner (ou simule) que ele grava sozinho.", 14f, Ui.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(60), 0, 0)
            })
        }

        // agrupado por dia
        var lastDay = ""
        var weekKm = 0f; var weekN = 0
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        for (f in files) {
            val start = startOf(f)
            val t = Trip.load(f)
            if (start >= weekAgo) { weekKm += t.distanceKm; weekN++ }
            val day = dayLabel(start)
            if (day != lastDay) {
                lastDay = day
                col.addView(Ui.text(c, day, 12f, Ui.MUTED).apply { setPadding(dp(2), dp(14), 0, dp(6)) })
            }
            col.addView(sessionCard(f, t, start), cardLp())
        }

        root.addView(ScrollView(c).apply { addView(col); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // rodapé: total da semana + dica
        val foot = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(4)) }
        foot.addView(Ui.text(c, if (weekN > 0) "%.1f km em %d sessõe%s esta semana".format(weekKm, weekN, if (weekN == 1) "" else "s") else "", 12f, Ui.FAINT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        foot.addView(Ui.text(c, "⋮ renomeia, compartilha e apaga", 12f, Ui.FAINT))
        root.addView(foot)
        setContentView(root)
    }

    private fun sessionCard(f: File, t: Trip, start: Long): View {
        val c = this
        val dur = t.durationMs / 1000
        val durStr = if (dur >= 3600) "%d:%02d:%02d".format(dur / 3600, (dur % 3600) / 60, dur % 60)
                     else "%d:%02d".format(dur / 60, dur % 60)
        val custom = TripNames.get(c, f)
        val card = Ui.card(c, vertical = false).apply {
            isClickable = true
            setOnClickListener { startActivity(Intent(c, DatalogActivity::class.java).putExtra("trip", f.absolutePath)) }
            setOnLongClickListener { sessionMenu(f, t, start, durStr); true }
        }
        val texts = vcol()
        texts.addView(Ui.text(c, custom ?: hhmm(start), 14f, Ui.TEXT, bold = true))
        texts.addView(Ui.text(c, buildString {
            if (custom != null) { append(hhmm(start)); append(" · ") }
            append(durStr); append(" · máx ${t.maxSpeed.toInt()} km/h")
        }, 12f, Ui.MUTED))
        card.addView(texts)
        card.addView(Ui.text(c, "%.1f".format(t.distanceKm), 17f, Ui.TEXT, bold = true))
        card.addView(Ui.text(c, " km", 12f, Ui.MUTED))
        // ações explícitas da sessão (padrão Hondash: nada escondido só em gesto)
        card.addView(Ui.text(c, "⋮", 20f, Ui.MUTED, bold = true).apply {
            setPadding(dp(14), dp(6), dp(4), dp(6))
            setOnClickListener { sessionMenu(f, t, start, durStr) }
        })
        return card
    }

    private fun sessionMenu(f: File, t: Trip, start: Long, durStr: String) {
        val c = this
        val title = TripNames.get(c, f) ?: "${dayLabel(start)} ${hhmm(start)}"
        AlertDialog.Builder(c).setTitle(title)
            .setItems(arrayOf("Abrir linha do tempo", "Renomear", "Compartilhar imagem", "Apagar")) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(c, DatalogActivity::class.java).putExtra("trip", f.absolutePath))
                    1 -> {
                        val inp = EditText(c).apply { setText(TripNames.get(c, f) ?: "") ; hint = "ex.: Volta da serra" }
                        AlertDialog.Builder(c).setTitle("Nome da sessão").setView(inp)
                            .setPositiveButton("OK") { _, _ -> TripNames.set(c, f, inp.text.toString()); render() }
                            .setNegativeButton("Cancelar", null).show()
                    }
                    2 -> shareTrip(t)
                    3 -> AlertDialog.Builder(c).setTitle("Apagar esta sessão?")
                        .setMessage("$title · $durStr")
                        .setPositiveButton("Apagar") { _, _ -> TripNames.set(c, f, null); TripLogger.delete(f); render() }
                        .setNegativeButton("Cancelar", null).show()
                }
            }.setNegativeButton("Fechar", null).show()
    }

    private fun shareTrip(t: Trip) {
        try {
            val bmp = TripImage.render(t)
            val dir = File(cacheDir, "shared"); dir.mkdirs()
            val f = File(dir, "viagem.png")
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Compartilhar viagem"))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Falha ao gerar imagem", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // --- helpers ---
    private fun vcol() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    private fun cardLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun dayLabel(ms: Long): String {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yest = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.timeInMillis = ms
        val day = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        return when (day) {
            today -> "Hoje"
            yest -> "Ontem"
            else -> SimpleDateFormat("EEEE dd/MM", Locale("pt", "BR")).format(Date(ms))
                .replaceFirstChar { it.uppercase() }
        }
    }

    private fun startOf(f: File): Long =
        f.name.removePrefix("trip_").removeSuffix(".csv").toLongOrNull() ?: f.lastModified()

    private fun dp(v: Int) = Ui.dp(this, v)
}
