package net.murilo.scanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Conecta no scanner via Bluetooth SPP/RFCOMM e fica em polling dos blocos,
 * preenchendo o [Dash]. Opcionalmente grava um log cru (para mapear sensores).
 */
@SuppressLint("MissingPermission")
class ScannerClient(
    private val device: BluetoothDevice,
    private val dash: Dash,
    private val onUpdate: () -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val TAG = "ScannerClient"
    @Volatile private var running = false
    val isRunning: Boolean get() = running
    private var thread: Thread? = null
    private var socket: BluetoothSocket? = null

    @Volatile var logFile: File? = null   // se != null, grava cada bloco
    @Volatile var clearDtcRequested = false   // setar p/ enviar o comando de limpar DTC no proximo ciclo

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "ScannerPoll").also { it.start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        thread?.interrupt()
    }

    private fun run() {
        // loop de reconexao: tenta conectar, lê ate cair, espera e tenta de novo (auto-reconectar)
        while (running) {
            try {
                status("Conectando…")
                val s = device.createRfcommSocketToServiceRecord(UUID.fromString(Protocol.SPP_UUID))
                socket = s
                s.connect()
                val input = s.inputStream
                val output = s.outputStream
                dash.connected = true
                status("Conectado")
                drain(input, 300)

                // Prioriza 0x00 (RPM+velocidade) e redesenha a cada leitura -> sem lag.
                // 0x10/0x20 (dados vivos) alternados; blocos quase estaticos bem espacados.
                val live = listOf(0x10 to 12, 0x20 to 16)
                // 0x40/0x50 = regiao dos codigos de erro (DTC); lidos no rodizio dos "rare".
                val rare = listOf(0x08 to 8, 0x3c to 1, 0x9a to 3, 0x76 to 2, 0x78 to 5, 0x40 to 16, 0x50 to 16)
                var li = 0; var ri = 0; var n = 0
                while (running) {
                    poll(input, output, 0x00, 3); onUpdate()                 // RPM + velocidade
                    if (!running) break
                    val lb = live[li]; li = (li + 1) % live.size
                    poll(input, output, lb.first, lb.second); onUpdate()     // 0x10 ou 0x20
                    if (!running) break
                    poll(input, output, 0x00, 3); onUpdate()                 // RPM de novo (fluido)
                    if (n % 8 == 0) { val rb = rare[ri]; ri = (ri + 1) % rare.size; poll(input, output, rb.first, rb.second) }
                    if (clearDtcRequested) {                                   // limpar DTC a pedido
                        try { output.write(Protocol.clearDtcCommand()); output.flush(); drain(input, 250) } catch (_: Exception) {}
                        dash.rawBlocks.remove(0x40); dash.rawBlocks.remove(0x50)
                        clearDtcRequested = false
                    }
                    n++
                    dash.pushHistory()
                }
            } catch (e: Exception) {
                Log.w(TAG, "erro", e)
                if (running) status("Reconectando…")
            } finally {
                dash.connected = false
                try { socket?.close() } catch (_: Exception) {}
            }
            if (running) try { Thread.sleep(1500) } catch (_: Exception) {}
        }
        status("Desconectado")
    }

    private fun poll(input: InputStream, output: OutputStream, addr: Int, len: Int) {
        val cmd = Protocol.readCommand(addr, len)
        output.write(cmd)
        output.flush()
        val reply = readExact(input, Protocol.totalReplyLen(len)) ?: return
        val data = Protocol.parseReply(cmd, reply, len)
        if (data != null) {
            dash.applyBlock(addr, data)
            logFile?.let { appendLog(it, addr, data) }
        } else {
            drain(input, 40)   // dessincronizou: limpa e segue
        }
    }

    /** Le exatamente [n] bytes (bloqueante). */
    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    private fun drain(input: InputStream, ms: Int) {
        val end = System.currentTimeMillis() + ms
        val tmp = ByteArray(256)
        try {
            while (System.currentTimeMillis() < end) {
                if (input.available() > 0) input.read(tmp) else Thread.sleep(5)
            }
        } catch (_: Exception) {}
    }

    private fun appendLog(f: File, addr: Int, data: ByteArray) {
        try {
            f.appendText("%d %02x %s\n".format(System.currentTimeMillis(), addr, data.joinToString("") { "%02x".format(it) }))
        } catch (_: Exception) {}
    }

    private fun status(s: String) { dash.statusMsg = s; onStatus(s) }
}
