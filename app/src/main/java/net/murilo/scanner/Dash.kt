package net.murilo.scanner

import java.util.concurrent.ConcurrentHashMap

/** Buffer circular simples para os mini-graficos (sparklines). */
class History(val size: Int = 120) {
    private val buf = FloatArray(size)
    private var count = 0
    private var head = 0
    @Synchronized fun push(v: Float) {
        buf[head] = v; head = (head + 1) % size; if (count < size) count++
    }
    @Synchronized fun snapshot(): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = buf[(head - count + i + size) % size]
        return out
    }
}

/**
 * Estado atual do painel. O scanner (thread BT) escreve; a View / Android Auto le.
 * Campos voláteis para leitura consistente o suficiente para desenhar.
 */
class Dash {
    @Volatile var connected = false
    @Volatile var statusMsg = "Desconectado"

    @Volatile var rpm = 0f          // REV
    @Volatile var speed = 0f        // km/h
    @Volatile var gear = 0          // GR
    @Volatile var ect = 0f          // temp liquido (C)
    @Volatile var iat = 0f          // temp ar admissao (C)
    @Volatile var tps = 0f          // %
    @Volatile var map = 0f          // kPa
    @Volatile var battery = 0f      // V
    @Volatile var ignAdv = 0f       // avanco ignicao (graus)
    @Volatile var injDur = 0f       // injecao (ms)
    @Volatile var o2Short = 0f      // trim combustivel short term (%)  (STF)
    @Volatile var ltf = 0f          // trim combustivel long term (%)   (LTF)
    @Volatile var o2Volt = 0f       // sonda O2 (valor cru)
    @Volatile var lambda = 1f       // lambda estimado da sonda O2 (emulado, ~1.0)
    @Volatile var accel = 0f        // aceleracao longitudinal (G)
    @Volatile var vtec = false
    @Volatile var mil = false

    // Combustivel (calculado pelo FuelCalc a partir de injDur/rpm/map)
    @Volatile var fuelFlowLh = 0f   // vazao instantanea (L/h)
    @Volatile var economyKmL = 0f   // economia instantanea (km/L)
    @Volatile var tripFuelL = 0f    // combustivel gasto na viagem (L)
    @Volatile var fuelLevelL = 0f   // nivel estimado do tanque (L)
    @Volatile var fuelPct = 0f      // nivel estimado (%)
    @Volatile var autonomyKm = 0f   // autonomia estimada (km)

    // GPS (do aparelho)
    @Volatile var gpsLat = 0.0
    @Volatile var gpsLon = 0.0
    @Volatile var gpsSpeedKmh = 0f
    @Volatile var gpsHasFix = false

    // Radar proximo (aviso)
    @Volatile var radarDistM = -1f   // -1 = nenhum na frente
    @Volatile var radarMaxSpeed = 0  // limite do radar (km/h), 0 = desconhecido

    // Historico dos 6 paineis
    val hBattery = History()
    val hIgn = History()
    val hInj = History()
    val hMap = History()
    val hTps = History()
    val hEct = History()

    // Dados crus de cada bloco (para debug / mapeamento de sensores)
    val rawBlocks = ConcurrentHashMap<Int, ByteArray>()

    /** Chamado a cada bloco lido do scanner. */
    fun applyBlock(addr: Int, data: ByteArray) {
        rawBlocks[addr] = data
        SensorMap.apply(this, addr, data)
    }

    fun pushHistory() {
        FuelCalc.tick(this)       // atualiza combustivel/economia/autonomia a cada ciclo
        PerfMeter.tick(this)      // 0-100 / dyno
        Stats.tick(this)          // hodometro / horas / maximos vitalicios
        TripLogger.tick(this)     // registro AUTOMÁTICO: grava sempre que houver dados do carro
        hBattery.push(battery); hIgn.push(ignAdv); hInj.push(injDur)
        hMap.push(map); hTps.push(tps); hEct.push(ect)
    }
}

/**
 * Mapeamento byte -> sensor -> escala. Formulas CONFERIDAS (2026-10-21) contra valores
 * reais do Hondash, usando o log de motor ligado e o repo andgarriv/Honda-ECU-Data (mesmo
 * protocolo). Detalhes em /home/murilo/Desktop/hondash/PROTOCOLO-HONDASH-ECU.md secao 6.
 * Convencao: bloco 0xNN, offset k  ==  RAM 0xNN+k. 16-bit desta ECU e BIG-ENDIAN.
 * Este e o unico lugar que precisa mudar para ajustar escalas.
 */
object SensorMap {
    private fun u8(d: ByteArray, i: Int) = if (i < d.size) d[i].toInt() and 0xFF else 0
    private fun u16be(d: ByteArray, i: Int) = (u8(d, i) shl 8) or u8(d, i + 1)

    /** Temperatura (ECT/IAT): polinomio de 5o grau do Hondash (C1096w) = curva do termistor. */
    private fun temp(x: Int): Float {
        val d = x.toDouble()
        return (155.04149 - 3.0414878 * d + 0.03952185 * d * d - 2.9383913e-4 * d * d * d +
                1.0792568e-6 * d * d * d * d - 1.5618437e-9 * d * d * d * d * d).toFloat()
    }

    fun apply(dash: Dash, addr: Int, d: ByteArray) {
        when (addr) {
            // Bloco 0x00 (RAM 0x00-0x02): RPM (16-bit BE /4) + velocidade. Lido com mais
            // frequencia (nao e keep-alive). RPM = (b[0]*256 + b[1]) / 4 ; velocidade = b[2].
            0x00 -> {
                dash.rpm = (u16be(d, 0) / 4f)                      // [0:2] BE / 4
                dash.speed = u8(d, 2).toFloat()                    // [2] -> km/h (VSS)
                // Marcha: detector aprende as razoes RPM/velocidade (como o Hondash). N parado.
                dash.gear = GearDetector.update(dash.rpm, dash.speed, learn = true)
            }
            // Bloco 0x10 (RAM 0x10-0x1b): sensores analogicos principais.
            0x10 -> {
                dash.ect = temp(u8(d, 0))                          // 0x10 -> curva termistor
                dash.iat = temp(u8(d, 1))                          // 0x11 -> curva termistor
                dash.map = u8(d, 2) * 7.5f - 75f                   // 0x12 -> mBar   (C1095v)
                dash.tps = ((u8(d, 4) - 25) / 2.09f).coerceIn(0f, 100f) // 0x14 -> %  (C1097x)
                dash.o2Volt = u8(d, 5).toFloat()                   // 0x15 -> sonda O2 (cru)
                // Lambda estimado (emulado, como no Hondash): tensao = cru/51 (W5.g);
                // curva generica de banda estreita (lambda=1 em ~0,45V). So referencia.
                val o2v = (u8(d, 5) / 51f).coerceIn(0f, 1f)
                dash.lambda = (1.0f - 0.30f * (o2v - 0.45f)).coerceIn(0.80f, 1.20f)
                dash.battery = u8(d, 7) * 26f / 270f               // 0x17 -> V      (r)
            }
            // Bloco 0x20 (RAM 0x20-0x2f): trims, injecao e avanco.
            0x20 -> {
                dash.o2Short = (u8(d, 0) - 128) / 1.28f            // 0x20 -> STF % (128 = 0%)
                dash.ltf = (u8(d, 2) - 128) / 1.28f               // 0x22 -> LTF % (128 = 0%)
                dash.injDur = u16be(d, 4) / 312.5f                 // 0x24:25 BE -> ms (C1094u)
                dash.ignAdv = u8(d, 6) / 4f - 6f                   // 0x26 -> graus (C1093t)
            }
        }
    }
}
