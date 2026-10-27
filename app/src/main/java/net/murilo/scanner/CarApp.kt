package net.murilo.scanner

import android.graphics.Canvas
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator

/**
 * Entrada do Android Auto. Desenha o MESMO painel (DashboardRenderer) na superficie
 * que o Android Auto fornece para apps de navegacao.
 */
class DashCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = DashSession()
}

class DashSession : Session() {
    override fun onCreateScreen(intent: android.content.Intent): Screen = DashScreen(carContext)
}

class DashScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = ClusterRenderer(
        carContext,
        try { androidx.core.content.res.ResourcesCompat.getFont(carContext, R.font.rajdhani_bold) } catch (_: Exception) { null }
    )
    /** Reconecta ao ultimo scanner em segundo plano (acao "Conectar" da barra / abertura da tela). */
    private fun maybeAutoConnect() {
        val dash = Shared.dash ?: Dash().also { Shared.dash = it }
        ScannerConnector.autoConnect(carContext, dash, { }, { })
    }
    @Volatile private var container: SurfaceContainer? = null
    @Volatile private var drawing = false
    @Volatile private var stable: Rect? = null     // area estavel (segura) informada pelo AA
    @Volatile private var visible: Rect? = null     // area visivel (muda no split com o Spotify)
    private var thread: Thread? = null

    init {
        // garante que sempre ha um Dash para desenhar (senao a tela fica preta ate conectar/simular)
        if (Shared.dash == null) Shared.dash = Dash()
        GearDetector.init(carContext)
        Theme.init(carContext)
        Feat.init(carContext)
        BgTex.init(carContext)
        Layout.init(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(c: SurfaceContainer) { container = c; startLoop(); maybeAutoConnect() }
            override fun onSurfaceDestroyed(c: SurfaceContainer) { drawing = false; container = null }
            override fun onVisibleAreaChanged(visibleArea: Rect) { visible = Rect(visibleArea) }
            override fun onStableAreaChanged(stableArea: Rect) { stable = Rect(stableArea) }
        })
    }

    private fun startLoop() {
        if (drawing) return
        drawing = true
        thread = Thread {
            while (drawing) {
                val cont = container
                val surface = cont?.surface
                val dash = Shared.dash ?: Dash().also { Shared.dash = it }
                if (surface != null && surface.isValid) {
                    var canvas: Canvas? = null
                    try {
                        canvas = surface.lockCanvas(null)
                        canvas.drawColor(android.graphics.Color.BLACK)
                        val fw = cont.width; val fh = cont.height
                        // LARGURA respeita o split (Spotify do lado); ALTURA usa a tela toda ->
                        // sem barra preta no topo; a acao (hamburguer) flutua por cima.
                        val a = stable?.takeUnless { it.isEmpty } ?: visible?.takeUnless { it.isEmpty }
                        val l = (a?.left ?: 0).coerceIn(0, fw)
                        val r = (a?.right ?: fw).coerceIn(l, fw)
                        canvas.save()
                        canvas.clipRect(l, 0, r, fh)
                        canvas.translate(l.toFloat(), 0f)
                        renderer.draw(canvas, r - l, fh, dash)
                        canvas.restore()
                    } catch (_: Exception) {
                    } finally {
                        if (canvas != null) try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                    }
                }
                try { Thread.sleep(50) } catch (_: Exception) {}
            }
        }.also { it.start() }
    }

    override fun onGetTemplate(): Template {
        // NavigationTemplate exige >=1 acao na ActionStrip -> um hamburguer discreto que abre o menu.
        val menu = androidx.car.app.model.Action.Builder()
            .setIcon(
                androidx.car.app.model.CarIcon.Builder(
                    androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, R.drawable.ic_menu_hamburger)
                ).build()
            )
            .setOnClickListener { screenManager.push(MenuScreen(carContext)) }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(androidx.car.app.model.ActionStrip.Builder().addAction(menu).build())
            .build()
    }
}

/** Menu do Android Auto (aberto pelo hamburguer). Conectar + recalibrar marcha. */
class MenuScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = androidx.car.app.model.ItemList.Builder()
            .addItem(
                androidx.car.app.model.Row.Builder()
                    .setTitle("Conectar scanner")
                    .setOnClickListener {
                        val dash = Shared.dash ?: Dash().also { Shared.dash = it }
                        ScannerConnector.autoConnect(carContext, dash, { }, { })
                        screenManager.pop()
                    }.build()
            )
            .addItem(
                androidx.car.app.model.Row.Builder()
                    .setTitle("Layout do painel")
                    .addText(Layout.labels.getOrElse(Layout.selected) { "" })
                    .setOnClickListener { screenManager.push(LayoutScreen(carContext)) }
                    .build()
            )
            .addItem(
                androidx.car.app.model.Row.Builder()
                    .setTitle("Recalibrar marcha")
                    .setOnClickListener { GearDetector.reset(); screenManager.pop() }
                    .build()
            )
            .build()
        return androidx.car.app.model.ListTemplate.Builder()
            .setSingleList(list)
            .setTitle("Menu")
            .setHeaderAction(androidx.car.app.model.Action.BACK)
            .build()
    }
}

/** Seleciona o layout do painel no Android Auto. */
class LayoutScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val b = androidx.car.app.model.ItemList.Builder()
        Layout.labels.forEachIndexed { i, label ->
            b.addItem(
                androidx.car.app.model.Row.Builder()
                    .setTitle(if (i == Layout.selected) "✓  $label" else label)
                    .setOnClickListener { Layout.set(i); screenManager.pop() }
                    .build()
            )
        }
        return androidx.car.app.model.ListTemplate.Builder()
            .setSingleList(b.build())
            .setTitle("Layout")
            .setHeaderAction(androidx.car.app.model.Action.BACK)
            .build()
    }
}
