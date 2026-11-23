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
