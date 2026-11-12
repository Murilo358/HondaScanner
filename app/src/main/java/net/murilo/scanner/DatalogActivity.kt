package net.murilo.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Leitor da sessão: cursor fixo no centro com o gráfico correndo por baixo,
 *  valor grande por canal, e a barra inferior ☰ (canais) · ▶ (reproduzir) · linha do tempo · ⋮ (menu).
 *  Acima da barra, a data/hora exata do ponto e a janela visível. */
class DatalogActivity : Activity() {
    private lateinit var trip: Trip
    private lateinit var graph: DatalogView
    private lateinit var seek: SeekBar
    private lateinit var tsView: TextView
    private lateinit var spanView: TextView
    private var tripPath: String = ""
    private val h = Handler(Looper.getMainLooper())
    private var playing = false
    private lateinit var playBtn: TextView

    private val CYAN = Color.parseColor("#F2EDEB"); private val DIM = Color.parseColor("#8a8c92")

    private val channels by lazy {
        listOf(
            Channel("rpm", "Rotação do motor", "rpm", Color.parseColor("#E23C3C"), { it.rpm }, 0f, null),
            Channel("speed", "Velocidade", "km/h", Color.parseColor("#E8EDF1"), { it.speed }, 0f, null),
            Channel("ect", "Arrefecimento", "°C", Color.parseColor("#2DC06E"), { it.ect }),
            Channel("tps", "Acelerador", "%", Color.parseColor("#E0A32E"), { it.tps }, 0f, 100f),
            Channel("map", "MAP", "mBar", Color.parseColor("#4a8ad6"), { it.map }),
            Channel("lambda", "Lambda", "", Color.parseColor("#B388FF"), { it.lambda }, decimals = 2),
            Channel("iat", "Ar admissão", "°C", Color.parseColor("#4DD0E1"), { it.iat }),
            Channel("battery", "Bateria", "V", Color.parseColor("#E060B0"), { it.battery }, decimals = 1),
            Channel("inj", "Injeção", "ms", Color.parseColor("#FF7043"), { it.injDur }, decimals = 1),
            Channel("gear", "Marcha", "", Color.parseColor("#26C6A8"), { it.gear.toFloat() }, 0f, 6f),
            Channel("economy", "Consumo", "km/L", Color.parseColor("#9CCC65"), { it.economy }, 0f, null, decimals = 1, on = false)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        tripPath = intent.getStringExtra("trip") ?: ""
        if (tripPath.isEmpty()) { finish(); return }
        val f = File(tripPath)
        // loading SEMPRE (mesmo com o CSV em cache): rota/distância e o primeiro desenho de uma
        // viagem longa travam a tela um instante — sem isso o clique parecia morto
        setContentView(Ui.loading(this, "Carregando sessão…"))
        Thread {
            val t = Trip.of(f)
            t.distanceKm   // força os lazies pesados (rota limpa + distância) fora da UI
            runOnUiThread { if (!isFinishing) { trip = t; buildUi() } }
        }.start()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#101013")) }

        // cabeçalho fino: voltar + nome/data + resumo
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }
        val name = TripNames.get(this, File(tripPath))
        val dateStr = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(trip.startMs))
        head.addView(TextView(this).apply {
            text = "‹"; textSize = 24f; setTextColor(DIM); setPadding(dp(6), 0, dp(14), dp(2))
            setOnClickListener { finish() }
        })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(TextView(this).apply {
            text = name ?: "Viagem $dateStr"; setTextColor(Color.WHITE); textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val dur = trip.durationMs / 1000
        val durStr = if (dur >= 3600) "%d:%02d:%02d".format(dur / 3600, (dur % 3600) / 60, dur % 60)
                     else "%d:%02d".format(dur / 60, dur % 60)
        titles.addView(TextView(this).apply {
            text = buildString {
                if (name != null) { append(dateStr); append(" · ") }
                append("%.1f km · %s · máx %d km/h".format(trip.distanceKm, durStr, trip.maxSpeed.toInt()))
            }
            setTextColor(DIM); textSize = 12f
        })
        head.addView(titles)
        root.addView(head)

        graph = DatalogView(this).apply {
            this.trip = this@DatalogActivity.trip
            this.channels = this@DatalogActivity.channels
            onScrub = { i -> syncTo(i) }
            onWindow = { ms -> spanView.text = fmtSpan(ms) }
        }
        root.addView(ScrollView(this).apply { isFillViewport = false; addView(graph); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // linha de info: data/hora do cursor + janela visível
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#17171a")); setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        tsView = TextView(this).apply {
            setTextColor(Color.parseColor("#c9ccd1")); textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        spanView = TextView(this).apply { setTextColor(DIM); textSize = 12.5f; typeface = android.graphics.Typeface.MONOSPACE }
        info.addView(tsView); info.addView(spanView)
        root.addView(info)

        // barra de controle: ☰ canais · ▶ reproduzir · linha do tempo · ⋮ menu
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1d1d21")); setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(barBtn("☰") { channelDialog() })
        playBtn = barBtn("▶") { togglePlay() }
        bar.addView(playBtn)
        seek = SeekBar(this).apply {
            max = (trip.samples.size - 1).coerceAtLeast(0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { graph.index = p; updateTs(p) } }
                override fun onStartTrackingTouch(s: SeekBar?) { stopPlay() }
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        bar.addView(seek)
        bar.addView(barBtn("⋮") { moreMenu() })
        root.addView(bar)

        setContentView(root)
        if (trip.samples.isNotEmpty()) { syncTo(0); spanView.text = fmtSpan(graph.visibleSpanMs()) }
    }

    override fun onPause() { super.onPause(); stopPlay() }

    private fun syncTo(i: Int) { seek.progress = i; updateTs(i) }

    private fun updateTs(i: Int) {
        val s = trip.samples.getOrNull(i) ?: return
        tsView.text = SimpleDateFormat("dd 'de' MMM 'de' yyyy  HH:mm:ss.SSS", Locale("pt", "BR")).format(Date(s.t))
    }

    private fun fmtSpan(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 5400 -> "~%dh".format(s / 3600)
            s >= 90 -> "~%dmin".format(s / 60)
            else -> "~%ds".format(s)
        }
    }

    // reprodução 1x: avança o cursor em tempo real
    private val playTick = object : Runnable {
        override fun run() {
            if (!playing) return
            val s = trip.samples
            val target = s[graph.index].t + 100
            var i = graph.index
            while (i < s.size - 1 && s[i].t < target) i++
            graph.index = i; syncTo(i)
            if (i >= s.size - 1) { stopPlay(); return }
            h.postDelayed(this, 100)
        }
    }
    private fun togglePlay() { if (playing) stopPlay() else { playing = true; playBtn.text = "❚❚"; h.post(playTick) } }
    private fun stopPlay() { playing = false; if (this::playBtn.isInitialized) playBtn.text = "▶" }

    /** ☰ — lista de canais com marcação. */
    private fun channelDialog() {
        val names = channels.map { if (it.unit.isNotEmpty()) "${it.name}  [${it.unit}]" else it.name }.toTypedArray()
        val checked = channels.map { it.on }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Canais")
            .setMultiChoiceItems(names, checked) { dlg, which, isChecked ->
                if (!isChecked && channels.count { it.on } <= 1) {
                    (dlg as AlertDialog).listView.setItemChecked(which, true)
                    toast("Deixe ao menos um canal")
                } else {
                    channels[which].on = isChecked; graph.markDirty()
                }
            }
            .setPositiveButton("OK", null).show()
    }

    /** ⋮ — menu da sessão. */
    private fun moreMenu() {
        val items = arrayListOf<String>()
        if (trip.hasGps) items.add("Ver no mapa")
        items.add("Compartilhar imagem")
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Ver no mapa" -> startActivity(Intent(this, RouteMapActivity::class.java).putExtra("trip", tripPath))
                    "Compartilhar imagem" -> shareImage()
                }
            }.setNegativeButton("Fechar", null).show()
    }

    private fun barBtn(glyph: String, onClick: () -> Unit) = TextView(this).apply {
        text = glyph; textSize = 19f; setTextColor(CYAN); gravity = Gravity.CENTER
        minWidth = dp(46); minHeight = dp(46)
        setOnClickListener { onClick() }
    }

    private fun shareImage() {
        toast("Gerando imagem…")
        Thread {   // baixa tiles do mapa: fora da UI
            try {
                val bmp = TripImage.render(this, trip)
                val dir = File(cacheDir, "shared"); dir.mkdirs()
                val f = File(dir, "viagem.png")
                java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                runOnUiThread {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Compartilhar viagem"))
                }
            } catch (e: Exception) { runOnUiThread { toast("Falha ao gerar imagem") } }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
}
