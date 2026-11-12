package net.murilo.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

class MainActivity : Activity() {

    private lateinit var view: DashboardView
    private lateinit var root: FrameLayout
    private var client: ScannerClient? = null
    private var sim: Simulator? = null
    private var recording = false
    private lateinit var ham: Button
    private lateinit var statusChip: TextView
    private val RETRY_SECS = 3
    private var countdown = RETRY_SECS
    private var customizer: LinearLayout? = null
    private var coresBar: View? = null   // aba Cores: barra "o que está editando" flutuando NO TOPO da tela
    private var menuOverlay: View? = null
    private var menuCat = 0
    private var editOverlay: EditOverlay? = null
    private var editBar: LinearLayout? = null

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        view = DashboardView(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        // menu hamburguer (canto superior esquerdo)
        ham = Button(this).apply {
            text = "☰"; textSize = 20f
            setTextColor(Color.parseColor("#F2EDEB"))
            background = pill(Color.parseColor("#33000000"))
            setOnClickListener { showMenu() }
        }
        root.addView(ham, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(10), dp(10), 0, 0)
        })

        // indicador de conexão (contagem até a próxima tentativa / status); toque = tentar agora
        statusChip = TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#F2EDEB"))
            background = pill(Color.parseColor("#33000000"))
            setPadding(dp(14), dp(7), dp(14), dp(7)); visibility = View.GONE
            setOnClickListener { attemptNow() }
        }
        root.addView(statusChip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(12)
        })

        setContentView(root)
        // tela cheia (metodo moderno) + mantem ligada; sem scrollbars/overscroll (MIUI mostrava barras)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // preenche o display inteiro (recorte/camera + barra de navegacao) -> sem faixas laterais
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= 30)
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        root.overScrollMode = View.OVER_SCROLL_NEVER
        root.isScrollContainer = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false

        Shared.dash = view.dash
        GearDetector.init(this)
        Theme.init(this)
        Feat.init(this)
        FuelCalc.init(this)
        PerfMeter.init(this)
        Stats.init(this)
        Maint.init(this)
        RadarDb.init(this)
        TripLogger.init(this)
        Speaker.init(this)
        BgTex.init(this)
        Layout.init(this)

        // GPS p/ o registro de viagem (começa se já tiver permissão; senão pede)
        GpsProvider.start(this, view.dash)
        if (Build.VERSION.SDK_INT >= 23 && !GpsProvider.hasPerm(this))
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)

        UpdateCheck.check(this, manual = false)   // avisa se houver release mais novo

        // conexão automática (vem ligada): tenta ao abrir e o ticker reata sozinho depois
        if (ScannerConnector.isAutoConnect(this))
            client = ScannerConnector.autoConnect(this, view.dash,
                { view.postInvalidate() }, { runOnUiThread { view.postInvalidate() } })
        view.postDelayed(connTick, 1000)
    }

    // ticker de 1s: mostra o status/contagem na tela e, com a conexão automática ligada,
    // reata a cada RETRY_SECS enquanto não houver scanner nem simulação.
    private val connTick = object : Runnable {
        override fun run() {
            updateConnChip()
            // o grace do registro (fechar 15s após sumirem os dados) só roda dentro do tick();
            // quando a conexão cai ninguém mais chama ele via pushHistory — o ticker assume
            TripLogger.tick(view.dash)
            view.postDelayed(this, 1000)
        }
    }

    private fun startAutoConnect() {
        statusChip.text = "Conectando…"
        client = ScannerConnector.autoConnect(this, view.dash,
            { view.postInvalidate() }, { runOnUiThread { view.postInvalidate() } })
        countdown = RETRY_SECS
    }

    /** Toque no indicador: tenta conectar na hora (ou abre o seletor, na central sem scanner escolhido). */
    private fun attemptNow() {
        if (sim?.running == true || view.dash.connected) return
        if (needsNwdPick()) { showNwdPicker(); return }
        startAutoConnect()
    }

    /** Na central NWD, sem scanner escolhido ainda: precisa abrir o seletor (não dá pra auto-conectar). */
    private fun needsNwdPick() = NwdSppTransport.present(this) && ScannerConnector.sppMac(this) == null

    private fun updateConnChip() {
        if (sim?.running == true || view.dash.connected) {
            statusChip.visibility = View.GONE; countdown = RETRY_SECS; return
        }
        if (!ScannerConnector.isAutoConnect(this)) {
            statusChip.text = "Conexão automática desligada — toque para conectar"
            statusChip.visibility = View.VISIBLE; return
        }
        if (Shared.client?.isRunning == true) {               // já está tentando/conectando
            statusChip.text = view.dash.statusMsg.ifBlank { "Conectando…" }
            statusChip.visibility = View.VISIBLE; countdown = RETRY_SECS; return
        }
        if (needsNwdPick()) {                                  // central sem scanner escolhido
            statusChip.text = "Toque para escolher o scanner (Bluetooth da central)"
            statusChip.visibility = View.VISIBLE; return
        }
        countdown -= 1                                        // ocioso: conta até a próxima tentativa
        if (countdown <= 0) startAutoConnect()
        else statusChip.text = "Tentando conectar ao scanner em ${countdown}s"
        statusChip.visibility = View.VISIBLE
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat()
        setColor(color); setStroke(dp(1), Color.parseColor("#4dE23C3C"))
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    /** Menu principal como DIALOGO com lista rolavel (o PopupMenu cortava/nao rolava em tela cheia
     *  deitada por causa do FLAG_LAYOUT_NO_LIMITS). */
    /** Menu A1 — trilha de ÍCONES à esquerda + lista de destinos (data terminal). */
    private fun showMenu() {
        if (menuOverlay != null) { hideMenu(); return }
        hideCustomizer()
        ham.visibility = View.GONE
        val mb = Theme.menuBg; val ac = Theme.menuAccent; val mut = Color.parseColor("#8A8C92")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Theme.withAlpha(mb, 0xF6))
        }
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(mb); setPadding(0, dp(8), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val contCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(22)) }
        val content = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f) }
        content.addView(contCol)

        val cats = listOf(
            "Painel" to R.drawable.ic_m_gauge, "Registro" to R.drawable.ic_m_log,
            "Motor" to R.drawable.ic_m_engine, "Radares" to R.drawable.ic_m_radar, "Cores" to R.drawable.ic_m_palette
        )
        val icons = ArrayList<android.widget.ImageView>()
        fun select(i: Int) {
            menuCat = i
            icons.forEachIndexed { idx, iv ->
                iv.setColorFilter(if (idx == i) ac else mut)
                iv.setBackgroundColor(if (idx == i) Theme.withAlpha(ac, 0x22) else Color.TRANSPARENT)
            }
            contCol.removeAllViews()
            // cabeçalho: nome da categoria com filete vermelho do tamanho do rótulo + ✕
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            titleCol.addView(TextView(this).apply { text = cats[i].first; setTextColor(Color.WHITE); textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            titleCol.addView(View(this).apply { setBackgroundColor(ac); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(1)).apply { topMargin = dp(3); width = dp(40) } })
            head.addView(titleCol)
            head.addView(TextView(this).apply { text = "✕"; setTextColor(mut); textSize = 20f; setPadding(dp(12), dp(4), dp(6), dp(4)); setOnClickListener { hideMenu() } })
            contCol.addView(head)
            contCol.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)) })
            if (i == 4) buildMenuColors(contCol) else buildCatList(contCol, i, mut)
        }
        cats.forEachIndexed { i, (name, icon) ->
            val iv = android.widget.ImageView(this).apply {
                setImageResource(icon); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(15), dp(13), dp(15), dp(13)); contentDescription = name
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(48))
                setOnClickListener { select(i) }
            }
            icons.add(iv); rail.addView(iv)
        }
        row.addView(rail); row.addView(content)
        root.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        menuOverlay = row
        select(menuCat.coerceIn(0, cats.size - 1))
    }

    private fun hideMenu() {
        menuOverlay?.let { root.removeView(it) }; menuOverlay = null
        ham.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            menuOverlay != null -> hideMenu()
            customizer != null -> hideCustomizer()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun reopenMenu() { hideMenu(); showMenu() }

    /** Cores do menu editadas AQUI dentro (fundo + destaque), aplicando ao vivo. */
    private fun buildMenuColors(parent: LinearLayout) {
        val bgs = intArrayOf(0xFF0A0A0A.toInt(), 0xFF000000.toInt(), 0xFF141414.toInt(), 0xFF0A141D.toInt(), 0xFF101820.toInt(), 0xFF1C1410.toInt())
        val accents = intArrayOf(0xFFE23C3C.toInt(), 0xFF2B82D8.toInt(), 0xFF49C0E0.toInt(), 0xFF2DC06E.toInt(), 0xFFE0A32E.toInt(), 0xFFB388FF.toInt(), 0xFFFF4081.toInt(), 0xFFFFFFFF.toInt())
        fun lbl(t: String) = parent.addView(TextView(this).apply { text = t; setTextColor(Color.parseColor("#9A9A9A")); textSize = 13f; setPadding(dp(2), dp(14), 0, dp(6)) })
        fun rowOf(colors: IntArray, key: String) {
            val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in colors) r.addView(swatch(c, dp(40)) { Theme.set(key, c); reopenMenu() })
            sc.addView(r); parent.addView(sc)
        }
        lbl("Fundo do menu"); rowOf(bgs, "menuBg")
        lbl("Cor de destaque"); rowOf(accents, "menuAccent")
        parent.addView(Button(this).apply {
            text = "Voltar ao preto + vermelho"; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
            background = pill(Theme.menuAccent); setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            setOnClickListener { Theme.set("menuBg", Theme.DEF_MENU_BG); Theme.set("menuAccent", Theme.DEF_MENU_ACCENT); reopenMenu() }
        })
    }

    /** Lista de destinos da categoria (texto + contagem mono à direita). */
    private fun buildCatList(parent: LinearLayout, cat: Int, mut: Int) {
        data class Dest(val label: String, val count: String?, val go: () -> Unit)
        val items = when (cat) {
            0 -> listOfNotNull(
                Dest("Personalizar", null) { hideMenu(); showCustomizer() },
                Dest(if (sim?.running == true) "Parar simulação" else "Simular dados", null) { hideMenu(); toggleSim() },
                Dest("Reiniciar marcha", null) { hideMenu(); GearDetector.reset(); toast("Marcha reiniciada") },
                Dest(if (view.dash.connected) "Reconectar scanner" else "Conectar scanner", null) { hideMenu(); pickAndConnect() },
                Dest("Conexão automática", if (ScannerConnector.isAutoConnect(this)) "ligada" else "desligada") {
                    val on = !ScannerConnector.isAutoConnect(this)
                    ScannerConnector.setAutoConnect(this, on)
                    if (!on && !view.dash.connected) { client?.stop(); Shared.client = null; client = null }
                    reopenMenu()
                },
                // central: desconectar e esquecer o scanner (libera o Bluetooth da central p/ o celular)
                if (NwdSppTransport.present(this) && ScannerConnector.sppMac(this) != null)
                    Dest("Desconectar scanner", "esquecer") { hideMenu(); forgetNwd() } else null,
                Dest("Buscar atualização", "v${BuildConfig.VERSION_NAME}") { hideMenu(); UpdateCheck.check(this, manual = true) }
            )
            // navegação NÃO fecha o menu: ao voltar da tela, o menu continua onde estava
            1 -> listOf(
                Dest("Ver sessões gravadas",
                    (if (TripLogger.active) "gravando · " else "") + "${TripLogger.list(this).size}") {
                    startActivity(Intent(this, TripsActivity::class.java))
                },
                Dest("Onde estacionei", null) { showParkedSpot() }
            )
            2 -> listOf(
                Dest("Diagnóstico (DTC)", null) { startActivity(Intent(this, DtcActivity::class.java)) },
                Dest("Desempenho", null) { startActivity(Intent(this, PerfActivity::class.java)) },
                Dest("Relatório / Saúde", null) { startActivity(Intent(this, ReportActivity::class.java)) },
                Dest("Manutenção", if (Maint.anyOverdue()) "vencida" else null) { startActivity(Intent(this, MaintActivity::class.java)) },
                Dest("Combustível · Alertas", null) { startActivity(Intent(this, SettingsActivity::class.java)) }
            )
            else -> listOf(
                Dest("Radares no mapa", null) { startActivity(Intent(this, RadarMapActivity::class.java)) },
                Dest("Atualizar radares", "${RadarDb.size()}") { showRadarMenu() }
            )
        }
        for (d in items) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true
                setPadding(dp(2), dp(14), dp(2), dp(14)); setOnClickListener { d.go() }
            }
            r.addView(TextView(this).apply { text = d.label; setTextColor(Color.WHITE); textSize = 18f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            if (d.count != null) r.addView(TextView(this).apply { text = d.count; setTextColor(mut); textSize = 15f; typeface = android.graphics.Typeface.MONOSPACE })
            parent.addView(r)
        }
    }

    /** Último ponto de GPS da sessão mais recente = onde o carro parou da última vez. */
    private fun showParkedSpot() {
        toast("Procurando a última posição…")
        Thread {
            val s = TripLogger.list(this).filter { it.length() > 200 }
                .firstNotNullOfOrNull { f -> Trip.of(f).route.lastOrNull() }
            runOnUiThread {
                if (s == null) toast("Nenhuma viagem com GPS ainda")
                else startActivity(Intent(this, RouteMapActivity::class.java)
                    .putExtra("lat", s.lat).putExtra("lon", s.lon).putExtra("when", s.t))
            }
        }.start()
    }

    private fun numInput(title: String, cur: Float, onOk: (Float) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (cur == cur.toInt().toFloat()) cur.toInt().toString() else cur.toString())
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().replace(',', '.').toFloatOrNull()?.let(onOk) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun fmtThr(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    private fun showFuelAlertsConfig() {
        val tkeys = Feat.thresholds.keys.toList()
        val items = arrayListOf(
            "⛽ Tanque cheio (acabei de abastecer)",
            "Capacidade do tanque: ${FuelCalc.tankL.toInt()} L",
            "Vazão do bico: ${FuelCalc.injCc.toInt()} cc/min",
            "Cilindros: ${FuelCalc.cylinders}",
            "Fator de calibração: ${FuelCalc.corr}",
            "Zerar combustível da viagem"
        )
        tkeys.forEach { items.add("${Feat.thresholds[it]}: ${fmtThr(Feat.threshold(it))}") }
        AlertDialog.Builder(this).setTitle("Combustível / Alertas")
            .setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    0 -> { FuelCalc.fillFull(); toast("Tanque marcado como cheio") }
                    1 -> numInput("Capacidade (L)", FuelCalc.tankL) { FuelCalc.setConfig(it, FuelCalc.injCc, FuelCalc.cylinders, FuelCalc.corr); showFuelAlertsConfig() }
                    2 -> numInput("Vazão do bico (cc/min)", FuelCalc.injCc) { FuelCalc.setConfig(FuelCalc.tankL, it, FuelCalc.cylinders, FuelCalc.corr); showFuelAlertsConfig() }
                    3 -> numInput("Cilindros", FuelCalc.cylinders.toFloat()) { FuelCalc.setConfig(FuelCalc.tankL, FuelCalc.injCc, it.toInt().coerceIn(1, 12), FuelCalc.corr); showFuelAlertsConfig() }
                    4 -> numInput("Fator de calibração", FuelCalc.corr) { FuelCalc.setConfig(FuelCalc.tankL, FuelCalc.injCc, FuelCalc.cylinders, it); showFuelAlertsConfig() }
                    5 -> { FuelCalc.resetTrip(view.dash); toast("Viagem zerada") }
                    else -> { val k = tkeys[i - 6]; numInput(Feat.thresholds[k]!!, Feat.threshold(k)) { Feat.setThreshold(k, it); showFuelAlertsConfig() } }
                }
            }
            .setNegativeButton("Fechar", null).show()
    }

    private val PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFB0BEC5.toInt(), 0xFF8D9196.toInt(), 0xFF000000.toInt(),
        0xFF0A141D.toInt(), 0xFF2B82D8.toInt(), 0xFF00B0FF.toInt(), 0xFF00E5FF.toInt(),
        0xFF00E676.toInt(), 0xFFFFD600.toInt(), 0xFFFF9100.toInt(), 0xFFE23C3C.toInt(),
        0xFFFF4081.toInt(), 0xFF7C4DFF.toInt(), 0xFFEAF4FF.toInt(), 0xFFC9F2F4.toInt()
    )
    private val COLOR_ELEMENTS = listOf(
        "bg" to "Fundo", "ring" to "Anel", "progress" to "Preenchimento", "needle" to "Ponteiro",
        "redline" to "Zona vermelha", "text" to "Texto", "griffin" to "Arte"
    )

    private fun arrowBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; textSize = 20f; isAllCaps = false; setTextColor(Color.parseColor("#F2EDEB"))
        background = null; minWidth = dp(44); minimumWidth = dp(44); minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(8), 0, dp(8), 0); setOnClickListener { onClick() }
    }
    private fun swatch(color: Int, size: Int, onClick: () -> Unit) = View(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat(); setColor(color or (0xFF shl 24)); setStroke(dp(1), 0x66FFFFFF)
        }
        layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(6) }
        setOnClickListener { onClick() }
    }
    private fun chip(label: String, initialOn: Boolean, onChange: (Boolean) -> Unit): Button {
        val b = Button(this).apply {
            isAllCaps = false; textSize = 13f; setPadding(dp(12), dp(2), dp(12), dp(2))
            minWidth = 0; minimumWidth = 0; minHeight = dp(36); minimumHeight = dp(36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) }
        }
        var on = initialOn
        fun style() {
            b.setTextColor(if (on) Color.parseColor("#FFFFFF") else Color.parseColor("#F2EDEB"))
            b.background = pill(if (on) Color.parseColor("#E23C3C") else Color.parseColor("#33ffffff"))
            b.text = (if (on) "● " else "○ ") + label
        }
        b.setOnClickListener { on = !on; style(); onChange(on) }
        style(); return b
    }
    private fun rowH() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** Personalizar AO VIVO: painel inferior com abas; cada ajuste muda a tela principal na hora. */
    private fun showCustomizer() {
        if (customizer != null) { hideCustomizer(); return }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#ee0D0A0A"))
            setPadding(dp(10), dp(8), dp(10), dp(10)); elevation = dp(12).toFloat()
        }
        val content = FrameLayout(this)
        val tabsRow = rowH()
        val tabsInner = rowH()
        val tabNames = listOf("Cores", "Fundo", "Arte", "Elementos", "Shift", "Layout")
        val tabBtns = ArrayList<Button>()
        fun selectTab(i: Int) {
            tabBtns.forEachIndexed { idx, b ->
                b.setTextColor(if (idx == i) Color.parseColor("#FFFFFF") else Color.parseColor("#F2EDEB"))
                b.background = if (idx == i) pill(Color.parseColor("#E23C3C")) else pill(Color.parseColor("#33ffffff"))
            }
            content.removeAllViews()
            // shift light só aparece em rotação alta: na aba dele, força aceso pra dar pra ver/editar
            Theme.previewShift = (i == 4)
            Theme.editKey = null   // aba Cores seta de novo ao abrir (elRefresh)
            coresBar?.let { root.removeView(it) }; coresBar = null
            view.postInvalidate()
            content.addView(when (i) {
                0 -> buildCores()
                1 -> buildCycle(BgTex.labels, { BgTex.selected }, { BgTex.set(it) })
                2 -> buildCycle(listOf("Grifo", "Gato", "Fogo"), { Theme.gaugeArt }, { Theme.saveGaugeArt(it) })
                3 -> buildElementos()
                4 -> buildShift()
                else -> buildLayoutTab()
            })
        }
        tabNames.forEachIndexed { i, name ->
            val b = Button(this).apply {
                text = name; isAllCaps = false; textSize = 13f; setPadding(dp(11), dp(2), dp(11), dp(2))
                // Button tem minWidth de 88dp por padrão: sem zerar, as 5 abas não cabem em pé
                minWidth = 0; minimumWidth = 0; minHeight = dp(36); minimumHeight = dp(36)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(5) }
                setOnClickListener { selectTab(i) }
            }
            tabBtns.add(b); tabsInner.addView(b)
        }
        // em pé as abas não cabem todas: ficam roláveis, com o Pronto sempre visível
        tabsRow.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(tabsInner)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabsRow.addView(Button(this).apply {
            text = "✓"; isAllCaps = false; textSize = 17f; setTextColor(Color.parseColor("#06220e"))
            background = pill(Color.parseColor("#2dc06e"))
            minWidth = 0; minimumWidth = 0; minHeight = dp(36); minimumHeight = dp(36)
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = "Pronto"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) }
            setOnClickListener { hideCustomizer() }
        })
        panel.addView(tabsRow)
        panel.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))
        customizer = panel
        selectTab(0)
        // o painel só redesenha quando chega dado novo; pro pisca do elemento em edição andar
        // mesmo com tudo parado, força ~10 quadros/s enquanto o customizador está aberto
        view.postDelayed(object : Runnable {
            override fun run() { if (customizer != null) { view.invalidate(); view.postDelayed(this, 100) } }
        }, 100)
    }

    private fun hideCustomizer() {
        customizer?.let { root.removeView(it) }; customizer = null
        coresBar?.let { root.removeView(it) }; coresBar = null
        Theme.previewShift = false; Theme.editKey = null; view.postInvalidate()
    }

    /** Aba de ciclo (Fundo / Layout): ◀ nome ▶, aplica na hora. */
    private fun buildCycle(labels: List<String>, get: () -> Int, set: (Int) -> Unit): View {
        val row = rowH()
        val label = TextView(this).apply {
            setTextColor(Color.parseColor("#F2EDEB")); textSize = 16f; gravity = Gravity.CENTER; minWidth = dp(240)
        }
        fun refresh() { label.text = "${labels[get()]}   (${get() + 1}/${labels.size})" }
        fun cycle(d: Int) { val n = labels.size; set(((get() + d) % n + n) % n); refresh(); view.postInvalidate() }
        row.addView(arrowBtn("◀") { cycle(-1) }); row.addView(label); row.addView(arrowBtn("▶") { cycle(1) })
        refresh(); return row
    }

    private fun buildElementos(): View {
        val sc = HorizontalScrollView(this)
        val row = rowH()
        for ((key, label) in Feat.labels) {
            row.addView(chip(label, Feat.on(key)) { on -> Feat.toggle(key, on); view.postInvalidate() })
        }
        sc.addView(row); return sc
    }

    private fun buildShift(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var rpm = Theme.shiftRpm
        val onRow = rowH()
        onRow.addView(chip("Shift light ligado", Theme.shiftEnabled) { on -> Theme.setShift(on, rpm); view.postInvalidate() })
        col.addView(onRow)
        val lbl = TextView(this).apply { setTextColor(Color.parseColor("#F2EDEB")); textSize = 14f; text = "RPM de troca: $rpm"; setPadding(0, dp(8), 0, dp(2)) }
        col.addView(lbl)
        col.addView(SeekBar(this).apply {
            max = (8000 - 3000) / 100; progress = ((rpm - 3000) / 100).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    rpm = 3000 + p * 100; lbl.text = "RPM de troca: $rpm"
                    Theme.setShift(Theme.shiftEnabled, rpm); view.postInvalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        val cr = rowH().apply { setPadding(0, dp(8), 0, 0) }
        cr.addView(TextView(this).apply { text = "Cor:"; setTextColor(Color.parseColor("#F2EDEB")); setPadding(0, 0, dp(8), 0) })
        val sc = HorizontalScrollView(this); val sw = rowH()
        for (pc in PRESETS) sw.addView(swatch(pc, dp(30)) { Theme.set("shiftColor", pc); view.postInvalidate() })
        sc.addView(sw); cr.addView(sc)
        col.addView(cr)
        return col
    }

    private fun buildCores(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var ci = 0
        val seeks = arrayOfNulls<SeekBar>(3)
        fun curKey() = COLOR_ELEMENTS[ci].first
        val preview = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(36), dp(28)).apply { leftMargin = dp(8) } }
        fun updPreview() {
            preview.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat(); setColor(Theme.get(curKey()) or (0xFF shl 24)); setStroke(dp(1), 0x66FFFFFF)
            }
        }
        fun loadSliders() {
            val c = Theme.get(curKey())
            seeks[0]?.progress = Color.red(c); seeks[1]?.progress = Color.green(c); seeks[2]?.progress = Color.blue(c)
        }
        val elLabel = TextView(this).apply { setTextColor(Color.parseColor("#F2EDEB")); textSize = 15f; gravity = Gravity.CENTER; minWidth = dp(150) }
        // o elemento selecionado PISCA no painel (ClusterRenderer) pra mostrar o que está sendo editado
        fun elRefresh() { elLabel.text = COLOR_ELEMENTS[ci].second; loadSliders(); updPreview(); Theme.editKey = curKey(); view.postInvalidate() }
        // "o que está editando" fica NO TOPO DA TELA (barra flutuante), não dentro do painel de baixo
        val elRow = rowH().apply {
            background = pill(Color.parseColor("#ee0D0A0A")); elevation = dp(12).toFloat()
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        elRow.addView(arrowBtn("◀") { ci = (ci - 1 + COLOR_ELEMENTS.size) % COLOR_ELEMENTS.size; elRefresh() })
        elRow.addView(elLabel)
        elRow.addView(arrowBtn("▶") { ci = (ci + 1) % COLOR_ELEMENTS.size; elRefresh() })
        elRow.addView(preview)
        root.addView(elRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(12) })
        coresBar = elRow
        // ordem: em cima O QUE edita (elRow), no meio o ajuste fino (R/G/B), embaixo AS CORES
        val sc = HorizontalScrollView(this); val swRow = rowH().apply { setPadding(0, dp(8), 0, dp(8)) }
        for (pc in PRESETS) swRow.addView(swatch(pc, dp(32)) { Theme.set(curKey(), pc); loadSliders(); updPreview(); view.postInvalidate() })
        sc.addView(swRow)
        val names = arrayOf("R", "G", "B")
        for (k in 0..2) {
            val r = rowH()
            r.addView(TextView(this).apply { text = names[k]; setTextColor(Color.parseColor("#F2EDEB")); width = dp(22) })
            val sb = SeekBar(this).apply {
                max = 255
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                        if (u) {
                            val c = Color.rgb(seeks[0]!!.progress, seeks[1]!!.progress, seeks[2]!!.progress)
                            Theme.set(curKey(), c); updPreview(); view.postInvalidate()
                        }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            seeks[k] = sb; r.addView(sb); col.addView(r)
        }
        val swBar = rowH()
        swBar.addView(sc, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        swBar.addView(Button(this).apply {
            text = "⟲ padrão"; isAllCaps = false; textSize = 12f; setTextColor(Color.parseColor("#F2EDEB"))
            background = pill(Color.parseColor("#33ffffff")); setPadding(dp(10), dp(2), dp(10), dp(2))
            minWidth = 0; minimumWidth = 0; minHeight = dp(36); minimumHeight = dp(36)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) }
            setOnClickListener { Theme.reset(); elRefresh(); view.postInvalidate() }
        })
        col.addView(swBar)
        elRefresh()
        return col
    }

    private fun smallBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12.5f; setTextColor(Color.parseColor("#F2EDEB"))
        background = pill(Color.parseColor("#33ffffff")); setPadding(dp(11), dp(2), dp(11), dp(2))
        minWidth = 0; minimumWidth = 0; minHeight = dp(36); minimumHeight = dp(36)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(6); topMargin = dp(2) }
        setOnClickListener { onClick() }
    }

    /** Aba "Layout": ciclo por todos (embutidos + personalizados) + CRUD dos personalizados. */
    private fun buildLayoutTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = rowH()
        val label = TextView(this).apply { setTextColor(Color.parseColor("#F2EDEB")); textSize = 16f; gravity = Gravity.CENTER; minWidth = dp(260) }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        lateinit var refresh: () -> Unit
        fun rebuildButtons() {
            btnRow.removeAllViews()
            btnRow.addView(smallBtn("+ Novo") {
                val input = EditText(this).apply { setText("Meu layout ${CustomStore.layouts.size + 1}") }
                AlertDialog.Builder(this).setTitle("Nome do layout").setView(input)
                    .setPositiveButton("Criar") { _, _ ->
                        val idx = CustomStore.add(input.text.toString().ifBlank { "Meu layout" })
                        hideCustomizer(); startEdit(idx)
                    }.setNegativeButton("Cancelar", null).show()
            })
            if (Layout.isCustom(Layout.selected)) {
                val ci = Layout.customIndex(Layout.selected)
                btnRow.addView(smallBtn("✏ Editar") { hideCustomizer(); startEdit(ci) })
                btnRow.addView(smallBtn("✎ Renomear") {
                    val input = EditText(this).apply { setText(CustomStore.get(ci)?.name ?: "") }
                    AlertDialog.Builder(this).setTitle("Renomear").setView(input)
                        .setPositiveButton("OK") { _, _ -> CustomStore.rename(ci, input.text.toString().ifBlank { "Layout" }); refresh(); view.postInvalidate() }
                        .setNegativeButton("Cancelar", null).show()
                })
                btnRow.addView(smallBtn("🗑 Apagar") {
                    AlertDialog.Builder(this).setTitle("Apagar layout?").setMessage(CustomStore.get(ci)?.name ?: "")
                        .setPositiveButton("Apagar") { _, _ -> CustomStore.delete(ci); Layout.set(0); refresh(); view.postInvalidate() }
                        .setNegativeButton("Cancelar", null).show()
                })
            }
        }
        refresh = { label.text = "${Layout.labels[Layout.selected]}   (${Layout.selected + 1}/${Layout.labels.size})"; rebuildButtons() }
        fun cycle(d: Int) { val n = Layout.labels.size; Layout.set(((Layout.selected + d) % n + n) % n); refresh(); view.postInvalidate() }
        row.addView(arrowBtn("◀") { cycle(-1) }); row.addView(label); row.addView(arrowBtn("▶") { cycle(1) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(btnRow) })
        col.addView(TextView(this).apply {
            text = "Com a tela em pé o painel usa o arranjo retrato; os layouts aparecem com a tela deitada."
            setTextColor(Color.parseColor("#8A8C92")); textSize = 12f; setPadding(dp(2), dp(8), 0, 0)
        })
        refresh()
        return col
    }

    /** Entra no modo de edicao do layout personalizado [ci]. */
    private fun startEdit(ci: Int) {
        val lay = CustomStore.get(ci) ?: return
        Layout.set(Layout.BUILTIN + ci)
        view.postInvalidate()
        val ov = EditOverlay(this).apply { widgets = lay.widgets }
        root.addView(ov, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        editOverlay = ov
        ham.visibility = View.GONE
        buildEditBar(lay)
        toast("Arraste pra mover · canto ◢ redimensiona · + adiciona widget")
    }

    private fun exitEdit() {
        editOverlay?.let { root.removeView(it) }; editOverlay = null
        editBar?.let { root.removeView(it) }; editBar = null
        CustomStore.save()
        ham.visibility = View.VISIBLE
        view.postInvalidate()
    }

    private fun buildEditBar(lay: CustomStore.CLayout) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#ee0D0A0A")); setPadding(dp(10), dp(6), dp(10), dp(8)); elevation = dp(12).toFloat()
        }
        bar.addView(TextView(this).apply { text = "✎ ${lay.name}"; setTextColor(Color.parseColor("#F2EDEB")); textSize = 14f; setPadding(0, 0, dp(10), 0) })
        val tools = rowH()
        tools.addView(smallBtn("＋ Adicionar") { addWidgetDialog() })
        tools.addView(smallBtn("🗑 Apagar") { editOverlay?.delSel() })
        tools.addView(smallBtn("🧲 Grade") { editOverlay?.let { it.snap = !it.snap; toast("Encaixe " + if (it.snap) "ligado" else "desligado") } })
        bar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(tools)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "✓ Sair"; isAllCaps = false; textSize = 14f; setTextColor(Color.parseColor("#06220e"))
            background = pill(Color.parseColor("#2dc06e")); setPadding(dp(16), dp(2), dp(16), dp(2))
            setOnClickListener { exitEdit() }
        })
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        editBar = bar
    }

    private fun addWidgetDialog() {
        val ov = editOverlay ?: return
        val specs = Widgets.catalog
        val items = specs.map { it.label }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Adicionar widget")
            .setItems(items) { _, i ->
                val sp = specs[i]
                ov.widgets.add(Widget(sp.type,
                    (0.5f - sp.w / 2f).coerceIn(0f, 1f - sp.w), (0.5f - sp.h / 2f).coerceIn(0f, 1f - sp.h), sp.w, sp.h))
                ov.sel = ov.widgets.size - 1; CustomStore.save(); ov.invalidate(); view.postInvalidate()
            }.show()
    }

    /** Camada transparente sobre o painel: desenha as alcas e trata o toque (arrastar/redimensionar). */
    inner class EditOverlay(ctx: Context) : View(ctx) {
        var widgets: MutableList<Widget> = mutableListOf()
        var sel = -1
        var snap = true
        private val pOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#80E23C3C") }
        private val pSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.parseColor("#2dc06e"); pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f) }
        private val pH = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2dc06e") }
        private var mode = 0
        private var dx0 = 0f; private var dy0 = 0f; private var ox = 0f; private var oy = 0f; private var ow = 0f; private var oh = 0f
        private val hs = dp(44).toFloat()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            for (i in widgets.indices) {
                val g = widgets[i]; val x = g.x * w; val y = g.y * h; val ww = g.w * w; val hh = g.h * h
                if (i == sel) {
                    c.drawRect(x, y, x + ww, y + hh, pSel)
                    c.drawRect(x + ww - hs / 2, y + hh - hs / 2, x + ww + hs / 2, y + hh + hs / 2, pH)
                } else c.drawRect(x, y, x + ww, y + hh, pOut)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val w = width.toFloat(); val h = height.toFloat(); val px = e.x; val py = e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (sel in widgets.indices) {
                        val g = widgets[sel]; val hx = (g.x + g.w) * w; val hy = (g.y + g.h) * h
                        if (Math.abs(px - hx) <= hs && Math.abs(py - hy) <= hs) { mode = 2; dx0 = px; dy0 = py; ow = g.w; oh = g.h; return true }
                    }
                    var hit = -1
                    for (i in widgets.indices.reversed()) {
                        val g = widgets[i]; val x = g.x * w; val y = g.y * h
                        if (px >= x && px <= x + g.w * w && py >= y && py <= y + g.h * h) { hit = i; break }
                    }
                    sel = hit; invalidate()
                    if (hit >= 0) { mode = 1; val g = widgets[hit]; dx0 = px; dy0 = py; ox = g.x; oy = g.y }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1 && sel in widgets.indices) {
                        val g = widgets[sel]
                        g.x = snapF(ox + (px - dx0) / w).coerceIn(0f, 1f - g.w)
                        g.y = snapF(oy + (py - dy0) / h).coerceIn(0f, 1f - g.h)
                        invalidate(); view.postInvalidate()
                    } else if (mode == 2 && sel in widgets.indices) {
                        val g = widgets[sel]
                        g.w = snapF(ow + (px - dx0) / w).coerceIn(0.05f, 1f - g.x)
                        g.h = snapF(oh + (py - dy0) / h).coerceIn(0.05f, 1f - g.y)
                        invalidate(); view.postInvalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (mode != 0) { mode = 0; CustomStore.save() }; return true }
            }
            return true
        }

        private fun snapF(v: Float) = if (snap) Math.round(v / 0.02f) * 0.02f else v
        fun delSel() { if (sel in widgets.indices) { widgets.removeAt(sel); sel = -1; CustomStore.save(); invalidate(); view.postInvalidate() } }
    }

    private fun toggleSim() {
        if (sim == null) sim = Simulator(view.dash) { view.postInvalidate() }
        val s = sim!!
        if (s.running) {
            s.stop()
            client = ScannerConnector.autoConnect(this, view.dash,
                { view.postInvalidate() }, { runOnUiThread { view.postInvalidate() } })
        } else {
            client?.stop(); Shared.client = null; client = null; s.start()
        }
    }

    private fun needPerm(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return true
        }
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) GpsProvider.start(this, view.dash)
            return
        }
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) pickAndConnect()
    }

    private fun showRadarMenu() {
        val on = Feat.on("radar")
        val items = arrayOf(
            "↻ Atualizar radares (tenho ${RadarDb.size()})",
            if (on) "Aviso de radar: LIGADO" else "Aviso de radar: desligado"
        )
        AlertDialog.Builder(this)
            .setTitle("Radares")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> { RadarDb.refreshPardal(); toast("Atualizando radares…") }
                    1 -> { Feat.toggle("radar", !on); toast(if (!on) "Aviso de radar ligado" else "Aviso de radar desligado") }
                }
            }.setNegativeButton("Fechar", null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val tmp = File(cacheDir, "radars_import.csv")
                contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                val n = RadarDb.importCsv(tmp)
                toast(if (n > 0) "$n radares importados" else "CSV vazio/inválido")
            } catch (_: Exception) { toast("Falha ao importar") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickAndConnect() {
        sim?.stop()
        // na central NWD o Bluetooth é do fabricante (não aparece no pareamento do Android) -> seletor próprio
        if (NwdSppTransport.present(this)) { showNwdPicker(); return }
        if (needPerm()) return
        val adapter: BluetoothAdapter? = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { toast("Ligue o Bluetooth"); return }
        val devices = adapter.bondedDevices.toList()
        if (devices.isEmpty()) { toast("Nenhum dispositivo pareado"); return }
        val names = devices.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Escolha o scanner").setItems(names) { _, i ->
            ScannerConnector.saveLast(this, devices[i].address)
            startWith(SocketTransport(devices[i]))
        }.show()
    }

    private fun startWith(transport: ScannerTransport) {
        client?.stop()
        client = ScannerClient(transport, view.dash,
            onUpdate = { view.postInvalidate() },
            onStatus = { runOnUiThread { view.postInvalidate() } })
        Shared.client = client
        if (recording) startLog()
        client!!.start()
    }

    /** Seletor de Bluetooth da central: procura, lista, você escolhe e (se preciso) digita o PIN. */
    private fun showNwdPicker() {
        val scan = NwdScan(this)
        val shown = ArrayList<NwdScan.Dev>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        val empty = TextView(this).apply {
            text = "Procurando dispositivos…\n(deixe o scanner ligado e por perto)"
            gravity = Gravity.CENTER; setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        val list = ListView(this).apply { this.adapter = adapter; emptyView = empty }
        val container = FrameLayout(this).apply { addView(empty); addView(list) }

        fun refresh() {
            shown.clear(); shown.addAll(scan.devices())
            adapter.clear()
            adapter.addAll(shown.map { (if (it.paired) "✓ " else "") + it.name + "\n" + it.mac })
            adapter.notifyDataSetChanged()
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Bluetooth da central — procurando…")
            .setView(container)
            .setNegativeButton("Fechar", null)
            .create()
        dlg.setOnDismissListener { Thread { scan.stop() }.start() }

        list.setOnItemClickListener { _, _, i, _ ->
            val dev = shown.getOrNull(i) ?: return@setOnItemClickListener
            val connect = { pin: String ->
                toast(if (dev.paired) "Conectando em ${dev.name}…" else "Pareando ${dev.name}…")
                Thread { scan.choose(dev, pin); runOnUiThread { dlg.dismiss(); startWith(NwdSppTransport(this)) } }.start()
            }
            if (dev.paired) connect("1234") else promptPin(dev.name, connect)
        }
        // segurar um item = desconectar e esquecer esse aparelho (libera o Bluetooth da central)
        list.setOnItemLongClickListener { _, _, i, _ ->
            val dev = shown.getOrNull(i) ?: return@setOnItemLongClickListener true
            if (ScannerConnector.sppMac(this) == dev.mac) ScannerConnector.clearSppMac(this)
            toast("Esquecendo ${dev.name}…")
            Thread { scan.forget(dev.mac); runOnUiThread { toast("Esquecido: ${dev.name}") } }.start()
            true
        }

        dlg.show()
        Thread {
            val ok = scan.start { runOnUiThread { refresh() } }
            runOnUiThread {
                if (!ok) { toast("Não consegui falar com o Bluetooth da central"); dlg.dismiss() }
                else { dlg.setTitle("Bluetooth da central"); refresh() }
            }
        }.start()
    }

    /** Central: desconecta o scanner E remove o pareamento, pra não reconectar sozinho e liberar o Bluetooth. */
    private fun forgetNwd() {
        val mac = ScannerConnector.sppMac(this)
        ScannerConnector.clearSppMac(this)                      // não reconecta mais até escolher de novo
        client?.stop(); Shared.client = null; client = null     // solta a SPP do nosso lado
        view.postInvalidate()
        toast("Desconectando e esquecendo o scanner…")
        Thread {
            val scan = NwdScan(this)
            if (scan.start(search = false) { }) {               // só pega os pareados (sem iniciar busca)
                Thread.sleep(800)                               // deixa chegar os registros de pareamento
                scan.forget(mac)                                // disConnectDevice_BC + deletePairDeviceRecord
            }
            scan.stop()
            runOnUiThread { toast("Scanner desconectado e esquecido") }
        }.start()
    }

    private fun promptPin(name: String, onPin: (String) -> Unit) {
        val input = EditText(this).apply { setText("1234"); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("PIN de $name")
            .setView(input)
            .setPositiveButton("Parear") { _, _ -> onPin(input.text.toString().ifBlank { "1234" }) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toggleRecord() {
        recording = !recording
        if (recording) startLog() else client?.logFile = null
        toast(if (recording) "Gravando" else "Gravação parada")
    }

    private fun startLog() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val f = File(dir, "captura_${System.currentTimeMillis()}.log")
        client?.logFile = f
        toast("Gravando em ${f.absolutePath}")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() { super.onDestroy(); view.removeCallbacks(connTick); client?.stop(); sim?.stop() }
}

/** Compartilha o estado com o servico do Android Auto. */
object Shared {
    @Volatile var dash: Dash? = null
    @Volatile var client: ScannerClient? = null
}
