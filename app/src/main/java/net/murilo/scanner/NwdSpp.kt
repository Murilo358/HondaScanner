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

        val mac = resolveMac() ?: throw IOException("escolha o scanner no Bluetooth da central (menu Conectar)")

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

    // O MAC vem do seletor (NwdScan), que já procurou/pareou e salvou. O transporte só reconecta nele.
    private fun resolveMac(): String? = ScannerConnector.sppMac(app)

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
        val intent = explicitIntent(app) ?: return null
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
        fun present(ctx: Context): Boolean = explicitIntent(ctx) != null

        internal fun explicitIntent(ctx: Context): Intent? {
            val i = Intent(ACTION_SERVICE)
            val info = ctx.packageManager.queryIntentServices(i, 0)?.firstOrNull()?.serviceInfo ?: return null
            return Intent(i).setComponent(ComponentName(info.packageName, info.name))
        }
    }
}

/**
 * Sessão de descoberta/pareamento pelo Bluetooth da central — para a tela "Conectar scanner"
 * se comportar como um Bluetooth normal: procura, lista, você escolhe, pede PIN e pareia.
 * Separada do [NwdSppTransport] (que só abre o canal de dados no MAC já salvo).
 *
 * Os métodos que falam com o serviço ([start], [choose], [stop]) podem bloquear (bind/IPC) —
 * chame fora da thread de UI.
 */
class NwdScan(ctx: Context) {
    /** Um aparelho visto na busca ou já pareado. [src] guarda o registro real quando veio de pareado. */
    data class Dev(val mac: String, var name: String, var paired: Boolean, var src: BTDevice? = null)

    private val app = ctx.applicationContext
    private var conn: ServiceConnection? = null
    private var feat: BtFeature? = null
    private var receiver: BroadcastReceiver? = null
    private val found = LinkedHashMap<String, Dev>()   // por MAC, preserva a ordem de chegada
    @Volatile private var onChange: (() -> Unit)? = null

    /** Liga no serviço; com [search] começa a procurar. [onChange] é chamado a cada aparelho novo/atualizado. */
    fun start(search: Boolean = true, onChange: () -> Unit): Boolean {
        this.onChange = onChange
        val binder = bind() ?: return false
        feat = BtFeature(binder)
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    NwdSppTransport.ACTION_BT_DEVICE_FOUND ->
                        add(i.getStringExtra(NwdSppTransport.EXTRA_ADDR), i.getStringExtra(NwdSppTransport.EXTRA_NAME), false, null)
                    NwdSppTransport.ACTION_PAIR_REC -> {
                        @Suppress("DEPRECATION") val d = i.getParcelableExtra<BTDevice>(NwdSppTransport.EXTRA_PAIR)
                        if (d?.address != null) add(d.address, d.name, d.isPair, d)
                    }
                    NwdSppTransport.ACTION_CONN_CHANGE -> this@NwdScan.onChange?.invoke()
                }
            }
        }
        receiver = rec
        app.registerReceiver(rec, IntentFilter().apply {
            addAction(NwdSppTransport.ACTION_BT_DEVICE_FOUND)
            addAction(NwdSppTransport.ACTION_PAIR_REC)
            addAction(NwdSppTransport.ACTION_CONN_CHANGE)
        })
        try { feat?.getPairRecord() } catch (_: Exception) {}       // já pareados
        if (search) try { feat?.startSearch() } catch (_: Exception) {}  // procura novos
        return true
    }

    fun devices(): List<Dev> = synchronized(found) { found.values.toList() }

    /** Desconecta o scanner no rádio e REMOVE o pareamento (para a central não reconectar sozinha). */
    fun forget(savedMac: String?) {
        val connected = try { feat?.getConnectDevice() } catch (_: Exception) { null }
        try { feat?.sppDisconnect() } catch (_: Exception) {}
        try { feat?.disconnectDevice() } catch (_: Exception) {}
        val idxs = LinkedHashSet<Int>()
        connected?.let { idxs.add(it.pairRecordIndex) }
        for (dv in devices()) dv.src?.let { if (savedMac == null || dv.mac == savedMac) idxs.add(it.pairRecordIndex) }
        for (i in idxs) try { feat?.deletePairRecord(i) } catch (_: Exception) {}
    }

    /** Pareia/conecta o escolhido com o [pin] e salva o MAC pro transporte reconectar. */
    fun choose(dev: Dev, pin: String): String {
        try { feat?.setPinCode(pin) } catch (_: Exception) {}
        val d = dev.src ?: BTDevice(dev.mac, dev.name).apply { deviceClass = 3; pairRecordIndex = -1 }
        d.obdPassword = pin
        try { feat?.connectDevice(d) } catch (_: Exception) {}
        ScannerConnector.saveSppMac(app, dev.mac)
        return dev.mac
    }

    fun stop() {
        try { feat?.stopSearch() } catch (_: Exception) {}
        receiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }; receiver = null
        conn?.let { try { app.unbindService(it) } catch (_: Exception) {} }; conn = null
        feat = null
    }

    private fun add(mac: String?, name: String?, paired: Boolean, src: BTDevice?) {
        if (mac == null) return
        synchronized(found) {
            val d = found.getOrPut(mac) { Dev(mac, name ?: mac, paired) }
            if (!name.isNullOrBlank()) d.name = name
            if (paired) d.paired = true
            if (src != null) d.src = src
        }
        onChange?.invoke()
    }

    private fun bind(): IBinder? {
        val intent = NwdSppTransport.explicitIntent(app) ?: return null
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
        } catch (_: Exception) { return null }
        latch.await(6, TimeUnit.SECONDS)
        return got
    }
}

/** Proxy do binder com.bt.BTFeature — só os métodos que o Scanner usa, com os transaction codes do firmware. */
private class BtFeature(private val b: IBinder) {
    fun registCb(cb: IBinder) = callVoid(40) { it.writeStrongBinder(cb) }
    fun unregistCb(cb: IBinder) = callVoid(41) { it.writeStrongBinder(cb) }
    fun setPinCode(pin: String) = callVoid(11) { it.writeString(pin) }
    fun connectDevice(dev: BTDevice): Boolean = callBool(4) { it.writeInt(1); dev.writeToParcel(it, 0) }
    fun disconnectDevice(): Boolean = callBool(5) {}             // disConnectDevice_BC
    fun startSearch(): Boolean = callBool(7) {}
    fun stopSearch(): Boolean = callBool(8) {}
    fun getPairRecord(): Boolean = callBool(31) {}
    fun deletePairRecord(index: Int): Boolean = callBool(32) { it.writeInt(index) }
    fun getConnectDevice(): BTDevice? {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(NwdSppTransport.DESC_FEATURE)
            b.transact(38, d, r, 0); r.readException()
            return if (r.readInt() != 0) BTDevice.CREATOR.createFromParcel(r) else null
        } finally { r.recycle(); d.recycle() }
    }
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
