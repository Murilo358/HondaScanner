package net.murilo.scanner

import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * Alimenta o [Dash] com dados simulados de um carro andando (para ver o painel sem o scanner).
 * Acionado pelo botao play / menu. Exclui o scanner: quem liga um, desliga o outro.
 */
class Simulator(private val dash: Dash, private val onUpdate: () -> Unit) {
    @Volatile var running = false
        private set
    private var t = 0.0

    fun start() {
        if (running) return
        running = true
        dash.statusMsg = "Simulando"
        thread(name = "Simulator") {
            while (running) {
                step()
                dash.pushHistory()
                onUpdate()
                try { Thread.sleep(33) } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        running = false
        dash.statusMsg = if (dash.connected) "Conectado" else "Desconectado"
    }

    fun toggle() { if (running) stop() else start() }

    private fun step() {
        t += 0.03
        val thr = ((sin(t * 0.5) + 1) / 2).toFloat()     // acelerador 0..1
        dash.tps = thr * 100f
        dash.rpm = 900f + thr * 6400f + (sin(t * 6) * 80).toFloat()
        dash.speed = thr * 170f
        dash.gear = when {
            dash.speed < 2f -> 0; dash.speed < 25f -> 1; dash.speed < 50f -> 2
            dash.speed < 85f -> 3; dash.speed < 125f -> 4; else -> 5
        }
        dash.map = 200f + thr * 720f
        dash.injDur = 1.8f + thr * 7f
        dash.ignAdv = 10f + thr * 24f
        dash.ect = if (dash.ect < 40f) 60f else minOf(98f, dash.ect + 0.015f)
        dash.iat = 34f + (sin(t * 0.3) * 6f).toFloat()                     // ar: ~28-40
        dash.battery = 14.3f - thr * 0.4f + (sin(t * 2) * 0.08f).toFloat() // cai um pouco sob carga
        // sonda O2 oscilando (malha fechada) -> lambda em torno de 1.0
        val o2osc = (sin(t * 4) * 0.35f).toFloat()
        dash.o2Volt = ((0.45f + o2osc) * 51f).coerceIn(0f, 255f)
        dash.lambda = (1.0f - thr * 0.06f - o2osc * 0.20f).coerceIn(0.80f, 1.20f)
    }
}
