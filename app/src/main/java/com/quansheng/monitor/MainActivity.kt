package com.quansheng.monitor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var btnConnect: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtLiveFreq: TextView
    private lateinit var txtLiveMeta: TextView
    private lateinit var logContainer: LinearLayout

    private var serialPort: UsbSerialPort? = null
    @Volatile private var isRunning = false
    private val seenSeqs = HashSet<Long>()
    private val csvLines = ArrayList<String>()

    private val ACTION_USB_PERMISSION = "com.quansheng.monitor.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connectUsb()
                    } else {
                        Toast.makeText(context, "Отказан достъп до USB", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(32, 48, 32, 24)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "UV-K6 Monitor"
            textSize = 20f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnConnect = Button(this).apply {
            text = "Свържи USB"
            setBackgroundColor(Color.parseColor("#38BDF8"))
            setTextColor(Color.parseColor("#0F172A"))
            setOnClickListener {
                if (!isRunning) requestUsbAndConnect() else disconnectUsb()
            }
        }
        header.addView(title)
        header.addView(btnConnect)
        root.addView(header)

        val liveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(32, 32, 32, 32)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 28, 0, 20)
            layoutParams = lp
        }

        txtStatus = TextView(this).apply {
            text = "ИЗКЛЮЧЕН"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
        }
        txtLiveFreq = TextView(this).apply {
            text = "---.----- MHz"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }
        txtLiveMeta = TextView(this).apply {
            text = "Включете кабела (OTG) и натиснете Свържи"
            textSize = 14f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
        }
        liveCard.addView(txtStatus)
        liveCard.addView(txtLiveFreq)
        liveCard.addView(txtLiveMeta)
        root.addView(liveCard)

        val btnBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        val btnSave = Button(this).apply {
            text = "Запази CSV"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveCsv() }
        }
        val btnClear = Button(this).apply {
            text = "Изчисти"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                logContainer.removeAllViews()
                seenSeqs.clear()
                csvLines.clear()
            }
        }
        btnBar.addView(btnSave)
        btnBar.addView(btnClear)
        root.addView(btnBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(logContainer)
        root.addView(scroll)

        setContentView(root)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun requestUsbAndConnect() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.isEmpty()) {
            Toast.makeText(this, "Не е открит USB кабел!", Toast.LENGTH_LONG).show()
            return
        }
        val device = drivers.first().device
        if (!manager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(device, pi)
        } else {
            connectUsb()
        }
    }

    private fun connectUsb() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.isEmpty()) return

        val driver = drivers.first()
        val connection = manager.openDevice(driver.device) ?: return
        val port = driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            serialPort = port
            isRunning = true

            btnConnect.text = "Прекъсни"
            btnConnect.setBackgroundColor(Color.parseColor("#EF4444"))
            txtStatus.text = "СВЪРЗАН — ГОТОВНОСТ"
            txtLiveMeta.text = "Слушане на ефира..."

            startWorker()
        } catch (e: Exception) {
            Toast.makeText(this, "Грешка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun disconnectUsb() {
        isRunning = false
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
        btnConnect.text = "Свържи USB"
        btnConnect.setBackgroundColor(Color.parseColor("#38BDF8"))
        txtStatus.text = "ИЗКЛЮЧЕН"
        txtLiveFreq.text = "---.----- MHz"
        txtLiveFreq.setTextColor(Color.WHITE)
    }

    private fun startWorker() {
        thread {
            val rxBuf = ArrayList<Byte>(4096)
            val readTmp = ByteArray(1024)
            var lastKa = 0L
            var firstKa = true

            while (isRunning) {
                val now = System.currentTimeMillis()
                if (now - lastKa >= 800L) {
                    val flags: Byte = if (firstKa) 0x83.toByte() else 0x03.toByte()
                    firstKa = false
                    try {
                        serialPort?.write(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x05, flags), 200)
                    } catch (_: Exception) { break }
                    lastKa = now
                }

                try {
                    val n = serialPort?.read(readTmp, 100) ?: 0
                    for (i in 0 until n) rxBuf.add(readTmp.get(i))
                } catch (_: Exception) { break }

                while (rxBuf.size >= 6) {
                    var idx = -1
                    for (i in 0 until rxBuf.size - 1) {
                        if (rxBuf.get(i) == 0xAA.toByte() && rxBuf.get(i + 1) == 0x55.toByte()) {
                            idx = i; break
                        }
                    }
                    if (idx == -1) { rxBuf.clear(); break }
                    if (idx > 0) rxBuf.subList(0, idx).clear()
                    if (rxBuf.size < 6) break

                    val type = rxBuf.get(2).toInt() and 0xFF
                    val len = ((rxBuf.get(3).toInt() and 0xFF) shl 8) or (rxBuf.get(4).toInt() and 0xFF)
                    if (len > 4096) { rxBuf.subList(0, 2).clear(); continue }

                    val total = 5 + len + 1
                    if (rxBuf.size < total) break

                    val payload = ByteArray(len) { i -> rxBuf.get(5 + i) }
                    val trailer = rxBuf.get(5 + len)
                    rxBuf.subList(0, total).clear()

                    if (trailer != 0x0A.toByte()) continue
                    if (type == 0x05) handleLivePacket(payload)
                    else if (type == 0x06) handleRows(payload, 0)
                }
            }
            runOnUiThread { if (isRunning) disconnectUsb() }
        }
    }

    private fun handleLivePacket(payload: ByteArray) {
        if (payload.size < 4) return
        val status = payload.get(1).toInt() and 0xFF
        if ((status and 0x08) != 0) {
            runOnUiThread {
                txtStatus.text = "ВНИМАНИЕ"
                txtLiveMeta.text = "Включете RxTxLog от менюто на радиото!"
                txtLiveMeta.setTextColor(Color.parseColor("#FBBF24"))
            }
            return
        }
        var off = 4
        if (payload.size >= off + 25) {
            val row = unpackRow(payload, off)
            off += 25
            runOnUiThread {
                txtLiveMeta.setTextColor(Color.parseColor("#CBD5E1"))
                if ((status and 0x01) != 0 && row.freq > 0) {
                    txtStatus.text = "АКТИВЕН СИГНАЛ (${row.mode})"
                    txtStatus.setTextColor(Color.parseColor("#22C55E"))
                    txtLiveFreq.text = "${row.freqMhz} MHz"
                    txtLiveFreq.setTextColor(Color.parseColor("#22C55E"))
                    txtLiveMeta.text = "${row.chan} | ${row.dur}s | S:${row.meter}"
                } else {
                    txtStatus.text = "ГОТОВНОСТ (ТИШИНА)"
                    txtStatus.setTextColor(Color.parseColor("#94A3B8"))
                    txtLiveFreq.text = "---.----- MHz"
                    txtLiveFreq.setTextColor(Color.WHITE)
                    txtLiveMeta.text = "Слушане на ефира..."
                }
            }
        }
        if (payload.size > off) handleRows(payload, off)
    }

    private fun handleRows(data: ByteArray, startOff: Int) {
        var off = startOff
        while (off + 25 <= data.size) {
            val r = unpackRow(data, off)
            off += 25
            if (r.freq == 0L || r.seq == 0L || seenSeqs.contains(r.seq)) continue
            seenSeqs.add(r.seq)
            runOnUiThread { addLogRow(r) }
        }
    }

    data class LogRow(val freq: Long, val freqMhz: String, val seq: Long, val dur: Int, val chan: String, val mode: String, val meter: Int)

    private fun unpackRow(data: ByteArray, off: Int): LogRow {
        val bb = ByteBuffer.wrap(data, off, 25).order(ByteOrder.LITTLE_ENDIAN)
        val freq = bb.int.toLong() and 0xFFFFFFFFL
        val seq = bb.int.toLong() and 0xFFFFFFFFL
        val dur = bb.short.toInt() and 0xFFFF
        val ch = bb.short.toInt() and 0xFFFF
        val flags = bb.get().toInt() and 0xFF
        val meter = bb.get().toInt() and 0xFF
        bb.get()
        val nameBytes = ByteArray(10)
        bb.get(nameBytes)
        val name = String(nameBytes, Charsets.US_ASCII).substringBefore(0.toChar()).trim()
        val freqMhz = String.format(Locale.US, "%.5f", freq / 100000.0)
        val mode = if ((flags and 0x01) != 0) "TX" else "RX"
        val chan = if (ch == 0xFFFF) "VFO" else "CH-${ch + 1}" + if (name.isNotEmpty()) " ($name)" else ""
        return LogRow(freq, freqMhz, seq, dur, chan, mode, meter)
    }

    private fun addLogRow(r: LogRow) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        csvLines.add("$timeStr,${r.mode},${r.freqMhz},"${r.chan}",${r.dur},${r.meter}")
        val item = TextView(this).apply {
            text = "$timeStr | ${r.mode} | ${r.freqMhz} MHz | ${r.chan} | ${r.dur}s | S:${r.meter}"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(if (r.mode == "TX") Color.parseColor("#F87171") else Color.parseColor("#4ADE80"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(20, 16, 20, 16)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 8)
            layoutParams = lp
        }
        logContainer.addView(item, 0)
    }
    private fun saveCsv() {
        if (csvLines.isEmpty()) {
            Toast.makeText(this, "Няма записи за запазване", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, "UVK6_Log_${System.currentTimeMillis()}.csv")
            file.writeText("Time,Mode,Frequency_MHz,Channel,Duration_s,S_Meter\n" + csvLines.joinToString("\n"))
            Toast.makeText(this, "Запазено в Downloads: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Грешка при запис: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        disconnectUsb()
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }
}
