package net.murilo.scanner

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.bt.BTDevice
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Transporte do scanner usando o Bluetooth da central NWD (K2401), que NÃO expõe a pilha
 * Bluetooth padrão do Android — o rádio é controlado pelo serviço do fabricante `com.bt.bc03.BTService`.
 *
 * Fala com o binder `com.bt.BTFeature` (mesmo que o app "Ferramentas → Conectar OBD" usa), que tem
 * passthrough SPP cru: SppConnect(mac) / SppSendData(bytes) / registSppDataCallback(onSppDataCallback).
 * Transaction codes e descriptors extraídos do firmware — precisam bater exatamente com o serviço.
 */
class NwdSppTransport(ctx: Context) : ScannerTransport {
    override val label = "Bluetooth da central"
    private val app = ctx.applicationContext

    private var conn: ServiceConnection? = null
    private var feat: BtFeature? = null
    @Volatile private var fifo = ByteFifo()
    private var connReceiver: BroadcastReceiver? = null
    private val cb = SppCallback { data, len -> fifo.feed(data, len) }

    override fun open(): Pair<InputStream, OutputStream> {
        val binder = bindBlocking() ?: throw IOException("serviço BT da central indisponível")
        val f = BtFeature(binder); feat = f
        fifo = ByteFifo()
        f.registCb(cb)
        try { f.setPinCode(PIN) } catch (_: Exception) {}

        val mac = resolveMac(f) ?: throw IOException("scanner OBD não encontrado no Bluetooth da central")
        ScannerConnector.saveSppMac(app, mac)

        if (!f.isSppConnect()) f.sppConnect(mac)
        val until = System.currentTimeMillis() + 8000
        while (!f.isSppConnect() && System.currentTimeMillis() < until) Thread.sleep(200)
        if (!f.isSppConnect()) throw IOException("SPP não conectou")

        watchDisconnect()
        return input() to output(f)
    }

    override fun close() {
        fifo.fail()
        try { feat?.unregistCb(cb) } catch (_: Exception) {}
        try { feat?.sppDisconnect() } catch (_: Exception) {}
        connReceiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }; connReceiver = null
        conn?.let { try { app.unbindService(it) } catch (_: Exception) {} }; conn = null
        feat = null
    }

    // --- descoberta do scanner ---
    @Volatile private var foundMac: String? = null

    private fun resolveMac(f: BtFeature): String? {
        ScannerConnector.sppMac(app)?.let { return it }   // já conectou antes: reusa
        foundMac = null
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    ACTION_BT_DEVICE_FOUND -> {
                        val n = i.getStringExtra(EXTRA_NAME) ?: ""
                        val a = i.getStringExtra(EXTRA_ADDR)
                        if (a != null && isObd(n)) foundMac = a
                    }
                    ACTION_PAIR_REC -> {
                        @Suppress("DEPRECATION") val d = i.getParcelableExtra<BTDevice>(EXTRA_PAIR)
                        if (d?.address != null && isObd(d.name ?: "")) foundMac = d.address
                    }
                }
            }
        }
        app.registerReceiver(rec, IntentFilter().apply {
            addAction(ACTION_PAIR_REC); addAction(ACTION_BT_DEVICE_FOUND)
        })
        try {
            // 1) já pareado?
            try { f.getPairRecord() } catch (_: Exception) {}
            waitFor(1500) { foundMac != null }
            if (foundMac == null) {
                // 2) procurar e parear
                try { f.startSearch() } catch (_: Exception) {}
                waitFor(30000) { foundMac != null }
                try { f.stopSearch() } catch (_: Exception) {}
                foundMac?.let { m ->
                    val dev = BTDevice(m, "OBD").apply { deviceClass = 3; obdPassword = PIN; pairRecordIndex = -1 }
                    try { f.connectDevice(dev); Thread.sleep(1500) } catch (_: Exception) {}
                }
            }
        } finally {
            try { app.unregisterReceiver(rec) } catch (_: Exception) {}
        }
        return foundMac
    }

    private fun watchDisconnect() {
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == ACTION_CONN_CHANGE && i.getIntExtra(EXTRA_CONN_EVENT, 0) != 1) fifo.fail()
            }
        }
        connReceiver = rec
        app.registerReceiver(rec, IntentFilter(ACTION_CONN_CHANGE))
    }

    private fun bindBlocking(): IBinder? {
        val intent = explicit(app) ?: return null
        val latch = CountDownLatch(1)
        var got: IBinder? = null
        val c = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, s: IBinder?) { got = s; latch.countDown() }
            override fun onServiceDisconnected(n: ComponentName?) {}
        }
        conn = c
        try {
            app.startService(intent)
            if (!app.bindService(intent, c, Context.BIND_AUTO_CREATE)) return null
        } catch (e: Exception) { Log.w(TAG, "bind falhou", e); return null }
        latch.await(6, TimeUnit.SECONDS)
        return got
    }

    private fun input() = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); val r = fifo.read(b, 0, 1); return if (r <= 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int = fifo.read(b, off, len)
    }

    private fun output(f: BtFeature) = object : OutputStream() {
        override fun write(b: Int) { f.sppSend(byteArrayOf(b.toByte())) }
        override fun write(b: ByteArray) { f.sppSend(b) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            f.sppSend(if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len))
        }
    }

    private fun waitFor(ms: Long, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < until) Thread.sleep(100)
    }

    private fun isObd(name: String) =
        name.contains("OBD", true) || name.contains("VHM", true) || name.contains("CAR", true)

    companion object {
        private const val TAG = "NwdSpp"
        const val PIN = "1234"

        const val ACTION_SERVICE = "com.bt.ACTION_START_SERVICE"
        const val DESC_FEATURE = "com.bt.BTFeature"
        const val DESC_CB = "com.bt.hd.link.SppDataCallBack"

        const val ACTION_BT_DEVICE_FOUND = "com.bt.ACTION_BT_DEVICE_FOUND"
        const val ACTION_CONN_CHANGE = "com.bt.ACTION_BT_CONNECTION_CHANGE"
        const val ACTION_PAIR_REC = "com.bt.ACTION_GET_PAIR_DEVICE_RECORD"
        const val EXTRA_ADDR = "extra_bt_device_address"
        const val EXTRA_NAME = "extra_bt_device_name"
        const val EXTRA_CONN_EVENT = "extra_bt_connection_event"
        const val EXTRA_PAIR = "extra_pair_device"

        /** true se a central tem o serviço de Bluetooth do fabricante (ou seja, não é um celular comum). */
        fun present(ctx: Context): Boolean = explicit(ctx) != null

        private fun explicit(ctx: Context): Intent? {
            val i = Intent(ACTION_SERVICE)
            val info = ctx.packageManager.queryIntentServices(i, 0)?.firstOrNull()?.serviceInfo ?: return null
            return Intent(i).setComponent(ComponentName(info.packageName, info.name))
        }
    }
}

/** Proxy do binder com.bt.BTFeature — só os métodos que o Scanner usa, com os transaction codes do firmware. */
private class BtFeature(private val b: IBinder) {
    fun registCb(cb: IBinder) = callVoid(40) { it.writeStrongBinder(cb) }
    fun unregistCb(cb: IBinder) = callVoid(41) { it.writeStrongBinder(cb) }
    fun setPinCode(pin: String) = callVoid(11) { it.writeString(pin) }
    fun connectDevice(dev: BTDevice): Boolean = callBool(4) { it.writeInt(1); dev.writeToParcel(it, 0) }
    fun startSearch(): Boolean = callBool(7) {}
    fun stopSearch(): Boolean = callBool(8) {}
    fun getPairRecord(): Boolean = callBool(31) {}
    fun sppConnect(mac: String): Boolean = callBool(47) { it.writeString(mac) }
    fun sppDisconnect() = callVoid(48) {}
    fun isSppConnect(): Boolean = callBool(50) {}
    fun sppSend(data: ByteArray) = callVoid(51) { it.writeByteArray(data) }

    private fun callVoid(code: Int, w: (Parcel) -> Unit) {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try { d.writeInterfaceToken(NwdSppTransport.DESC_FEATURE); w(d); b.transact(code, d, r, 0); r.readException() }
        finally { r.recycle(); d.recycle() }
    }

    private fun callBool(code: Int, w: (Parcel) -> Unit): Boolean {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try { d.writeInterfaceToken(NwdSppTransport.DESC_FEATURE); w(d); b.transact(code, d, r, 0); r.readException(); return r.readInt() != 0 }
        finally { r.recycle(); d.recycle() }
    }
}

/** Callback que o serviço chama com os bytes crus recebidos pela SPP (onSppDataCallback(byte[], int), code 1). */
private class SppCallback(private val onData: (ByteArray, Int) -> Unit) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == 1) {
            data.enforceInterface(NwdSppTransport.DESC_CB)
            val arr = data.createByteArray()
            val n = data.readInt()
            if (arr != null) onData(arr, if (n in 0..arr.size) n else arr.size)
            reply?.writeNoException()
            return true
        }
        if (code == android.os.IBinder.INTERFACE_TRANSACTION) { reply?.writeString(NwdSppTransport.DESC_CB); return true }
        return super.onTransact(code, data, reply, flags)
    }
}

/** Buffer bloqueante que transforma os callbacks SPP (assíncronos) em um InputStream, p/ reusar o loop de polling. */
private class ByteFifo {
    private val lock = Object()
    private var buf = ByteArray(4096)
    private var r = 0
    private var w = 0
    @Volatile private var dead = false

    fun feed(src: ByteArray, len: Int) = synchronized(lock) {
        ensure(len)
        System.arraycopy(src, 0, buf, w, len); w += len
        lock.notifyAll()
    }

    fun fail() = synchronized(lock) { dead = true; lock.notifyAll() }

    fun read(dst: ByteArray, off: Int, n: Int): Int = synchronized(lock) {
        while (r >= w && !dead) lock.wait()
        if (r >= w && dead) throw IOException("SPP desconectado")
        val c = minOf(n, w - r)
        System.arraycopy(buf, r, dst, off, c); r += c
        if (r == w) { r = 0; w = 0 }
        c
    }

    private fun ensure(len: Int) {
        if (w + len <= buf.size) return
        val used = w - r
        if (used + len <= buf.size) { System.arraycopy(buf, r, buf, 0, used); r = 0; w = used; return }
        var ns = buf.size * 2; while (ns < used + len) ns *= 2
        val nb = ByteArray(ns); System.arraycopy(buf, r, nb, 0, used); buf = nb; r = 0; w = used
    }
}
