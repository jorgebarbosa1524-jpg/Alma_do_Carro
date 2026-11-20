package br.com.almadocarro

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Implementa o contrato window.BoltNative que o projeto já tinha previsto (obd-native.js):
 *  - modo "spp": ELM327 Bluetooth clássico pareado;
 *  - modo "ble" (opção "Ponte nativa"): ELM327 Bluetooth LE e, no fim da lista, ELM327 Wi-Fi.
 * Os dados recebidos vão para o painel como texto, exatamente como os outros transportes.
 */
@SuppressLint("MissingPermission")
class ObdBridge(private val ctx: Context) {

    private interface Conn {
        fun write(text: String)
        fun close()
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val OBD_NAME = Regex("obd|elm|v-?link|vgate|konnwei|icar|veepeak|obdlink|carista|kiwi|scan|torque|ios-vlink|android-vlink", RegexOption.IGNORE_CASE)
        private val KNOWN_BLE = listOf(
            Triple("0000fff0", "0000fff1", "0000fff2"),
            Triple("0000ffe0", "0000ffe1", "0000ffe1"),
            Triple("000018f0", "00002af0", "00002af1"),
            Triple("e7810a71", "bef8d6c9", "bef8d6c9"),
        )
        private val IGNORED_SERVICES = setOf("00001800", "00001801", "0000180a", "0000180f")
        private const val PREFS = "alma_obd"
    }

    @Volatile private var conn: Conn? = null
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun needBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            throw IllegalStateException("Permita o acesso a 'Dispositivos por perto' para o Alma do Carro nas configurações do Android.")
        }
        val a = adapter ?: throw IllegalStateException("Este aparelho não tem Bluetooth.")
        if (!a.isEnabled) throw IllegalStateException("Ligue o Bluetooth do celular.")
    }

    /* ------------------------------------------------------------------ lista */

    fun list(mode: String): JSONArray {
        val items = ArrayList<Pair<String, String>>() // nome, endereço
        when (mode) {
            "spp" -> {
                needBtPermissions()
                adapter!!.bondedDevices.orEmpty()
                    .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
                    .forEach { items.add((it.name ?: it.address) to it.address) }
                if (items.isEmpty()) throw IllegalStateException("Nenhum adaptador pareado. Pareie o ELM327 nas configurações de Bluetooth do celular (PIN costuma ser 1234 ou 0000).")
            }
            "wifi" -> items.add(wifiItem())
            else -> { // "ble" / "native"
                if (adapter?.isEnabled == true && (Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_SCAN))) {
                    scanBle(4500).forEach { items.add(it) }
                }
                items.add(wifiItem())
            }
        }
        val last = prefs.getString("last_$mode", null)
        val sorted = items.distinctBy { it.second }.sortedWith(
            compareBy<Pair<String, String>>({ if (it.second == last) 0 else if (OBD_NAME.containsMatchIn(it.first)) 1 else 2 })
        )
        val chosen = if (sorted.size > 1 && sorted.first().second != last && !OBD_NAME.containsMatchIn(sorted.first().first)) {
            askUser(sorted) ?: throw IllegalStateException("Seleção cancelada.")
        } else sorted
        val arr = JSONArray()
        chosen.forEach { arr.put(JSONObject().put("name", it.first).put("address", it.second)) }
        return arr
    }

    private fun wifiItem(): Pair<String, String> {
        val hostPort = prefs.getString("wifi_target", "192.168.0.10:35000")!!
        return "ELM327 Wi-Fi ($hostPort)" to "tcp:$hostPort"
    }

    /** Vários aparelhos e nenhum com cara de OBD: pergunta qual é (se o celular estiver na tela). */
    private fun askUser(options: List<Pair<String, String>>): List<Pair<String, String>>? {
        val act = Alma.activity ?: return options
        val result = AtomicReference<List<Pair<String, String>>?>(null)
        val latch = CountDownLatch(1)
        Alma.main.post {
            try {
                AlertDialog.Builder(act)
                    .setTitle("Qual é o adaptador OBD2?")
                    .setItems(options.map { it.first }.toTypedArray()) { _, i -> result.set(listOf(options[i])); latch.countDown() }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            } catch (_: Throwable) {
                result.set(options); latch.countDown()
            }
        }
        latch.await(60, TimeUnit.SECONDS)
        return result.get()
    }

    private fun scanBle(ms: Long): List<Pair<String, String>> {
        val scanner = adapter?.bluetoothLeScanner ?: return emptyList()
        val found = LinkedHashMap<String, String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                synchronized(found) { found[result.device.address] = name }
            }
        }
        try {
            scanner.startScan(cb)
            Thread.sleep(ms)
        } catch (e: Throwable) {
            Log.w(Alma.TAG, "Busca BLE falhou", e)
        } finally {
            try { scanner.stopScan(cb) } catch (_: Throwable) { }
        }
        return synchronized(found) { found.map { (addr, name) -> name to addr } }
    }

    /* ------------------------------------------------------------------ conexão */

    fun connect(mode: String, address: String) {
        disconnect()
        val c: Conn = when {
            address.startsWith("tcp:") -> TcpConn(address.removePrefix("tcp:"))
            mode == "spp" -> SppConn(address)
            else -> BleConn(address)
        }
        conn = c
        prefs.edit().putString("last_$mode", address).apply()
    }

    fun write(text: String) {
        (conn ?: throw IllegalStateException("Adaptador não conectado.")).write(text)
    }

    fun disconnect() {
        val c = conn
        conn = null
        try { c?.close() } catch (_: Throwable) { }
    }

    private fun data(text: String) {
        if (text.isNotEmpty()) Alma.js("window.__almaNative&&__almaNative.obdData(${JSONObject.quote(text)})")
    }

    private fun closed(c: Conn, reason: String) {
        if (conn !== c) return
        conn = null
        Alma.js("window.__almaNative&&__almaNative.obdClose(${JSONObject.quote(reason)})")
    }

    private fun startReader(c: Conn, input: InputStream, label: String) {
        Thread({
            val buf = ByteArray(1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) data(String(buf, 0, n, Charsets.ISO_8859_1))
                }
            } catch (_: Throwable) {
            }
            closed(c, "$label desconectado")
        }, "alma-obd-reader").apply { isDaemon = true }.start()
    }

    /* ---------------- Bluetooth clássico ---------------- */

    private inner class SppConn(address: String) : Conn {
        private val socket: BluetoothSocket
        private val out: OutputStream

        init {
            needBtPermissions()
            val a = adapter!!
            val dev = a.getRemoteDevice(address)
            try { a.cancelDiscovery() } catch (_: Throwable) { }
            socket = openSocket(dev)
            out = socket.outputStream
            startReader(this, socket.inputStream, "Bluetooth")
        }

        private fun openSocket(dev: BluetoothDevice): BluetoothSocket {
            val attempts = listOf<() -> BluetoothSocket>(
                { dev.createRfcommSocketToServiceRecord(SPP_UUID) },
                { dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                { dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket },
            )
            var last: Throwable? = null
            for (make in attempts) {
                try {
                    val s = make()
                    s.connect()
                    return s
                } catch (e: Throwable) {
                    last = e
                }
            }
            throw IllegalStateException("Não conectou ao adaptador Bluetooth (${last?.message ?: "sem resposta"}). Confira se ele está ligado na tomada OBD e com a ignição ligada.")
        }

        override fun write(text: String) {
            out.write(text.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        override fun close() {
            try { socket.close() } catch (_: Throwable) { }
        }
    }

    /* ---------------- Wi-Fi (TCP) ---------------- */

    private inner class TcpConn(hostPort: String) : Conn {
        private val socket = Socket()
        private val out: OutputStream

        init {
            val host = hostPort.substringBeforeLast(":")
            val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 35000
            // O Wi-Fi do adaptador não tem internet: força o uso dele (e não dos dados móveis).
            try {
                val cm = ctx.getSystemService(ConnectivityManager::class.java)
                cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                    ?.bindSocket(socket)
            } catch (_: Throwable) {
            }
            try {
                socket.connect(InetSocketAddress(host, port), 6000)
            } catch (e: Throwable) {
                throw IllegalStateException("Não conectou ao ELM327 Wi-Fi em $host:$port. Conecte o celular na rede Wi-Fi do adaptador.")
            }
            socket.tcpNoDelay = true
            out = socket.getOutputStream()
            startReader(this, socket.getInputStream(), "Wi-Fi")
        }

        override fun write(text: String) {
            out.write(text.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        override fun close() {
            try { socket.close() } catch (_: Throwable) { }
        }
    }

    /* ---------------- Bluetooth LE ---------------- */

    private inner class BleConn(address: String) : Conn {
        private var gatt: BluetoothGatt? = null
        private var writeChar: BluetoothGattCharacteristic? = null
        private val ready = CountDownLatch(1)
        private var writeLatch: CountDownLatch? = null
        @Volatile private var failure: String? = null
        @Volatile private var open = true

        private val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try { g.requestMtu(185) } catch (_: Throwable) { g.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (ready.count > 0) { failure = "O adaptador BLE recusou a conexão (código $status)."; ready.countDown() }
                    if (open) closed(this@BleConn, "Bluetooth LE desconectado")
                    try { g.close() } catch (_: Throwable) { }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val pair = findChars(g)
                if (pair == null) {
                    failure = "Este aparelho BLE não parece ser um adaptador OBD2."
                    ready.countDown(); return
                }
                val (notify, write) = pair
                writeChar = write
                g.setCharacteristicNotification(notify, true)
                val d = notify.getDescriptor(CCCD)
                if (d == null) { ready.countDown(); return }
                val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value)
                else {
                    @Suppress("DEPRECATION")
                    d.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                ready.countDown()
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                writeLatch?.countDown()
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                data(String(value, Charsets.ISO_8859_1))
            }

            @Deprecated("API antiga")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33) return
                @Suppress("DEPRECATION")
                c.value?.let { data(String(it, Charsets.ISO_8859_1)) }
            }
        }

        init {
            needBtPermissions()
            val dev = adapter!!.getRemoteDevice(address)
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            if (!ready.await(15, TimeUnit.SECONDS)) {
                close()
                throw IllegalStateException("O adaptador BLE não respondeu. Confira se ele está ligado e perto do celular.")
            }
            failure?.let { close(); throw IllegalStateException(it) }
        }

        private fun findChars(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
            fun short(u: UUID) = u.toString().substring(0, 8).lowercase()
            for ((s, n, w) in KNOWN_BLE) {
                val svc = g.services.firstOrNull { short(it.uuid) == s } ?: continue
                val nc = svc.characteristics.firstOrNull { short(it.uuid) == n }
                val wc = svc.characteristics.firstOrNull { short(it.uuid) == w }
                if (nc != null && wc != null) return nc to wc
            }
            val canNotify = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
            val canWrite = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            for (svc in g.services) {
                if (short(svc.uuid) in IGNORED_SERVICES) continue
                val nc = svc.characteristics.firstOrNull { it.properties and canNotify != 0 } ?: continue
                val wc = svc.characteristics.firstOrNull { it.properties and canWrite != 0 } ?: continue
                return nc to wc
            }
            return null
        }

        override fun write(text: String) {
            val g = gatt ?: throw IllegalStateException("BLE fechado.")
            val c = writeChar ?: throw IllegalStateException("BLE sem canal de escrita.")
            val noResp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            text.toByteArray(Charsets.ISO_8859_1).toList().chunked(20).forEach { part ->
                val bytes = part.toByteArray()
                val latch = CountDownLatch(1)
                writeLatch = latch
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, bytes, type)
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = type
                    @Suppress("DEPRECATION")
                    c.value = bytes
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
                latch.await(if (noResp) 60 else 1500, TimeUnit.MILLISECONDS)
            }
        }

        override fun close() {
            open = false
            try { gatt?.disconnect() } catch (_: Throwable) { }
            try { gatt?.close() } catch (_: Throwable) { }
            gatt = null
        }
    }
}
