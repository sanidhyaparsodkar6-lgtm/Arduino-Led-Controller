package com.example.arduinoledcontroller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.arduinoledcontroller.USB_PERMISSION"
        private const val BAUD_RATE = 9600
        private const val BOOT_DELAY_MS = 2000L
    }

    private lateinit var usbManager: UsbManager
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var connectedDevice: UsbDevice? = null
    private var pendingDriver: UsbSerialDriver? = null
    @Volatile private var ready = false

    private val writer = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val rxBuffer = StringBuilder()
    private val logLines = ArrayList<String>()

    private lateinit var content: ViewGroup
    private lateinit var usbDot: View
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var speedLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var modeText: TextView
    private lateinit var effectsGrid: GridLayout
    private lateinit var btnEffectsHeader: MaterialButton
    private lateinit var logText: TextView

    private val ledDots = arrayOfNulls<View>(4)
    private val ledOnColors = intArrayOf(
        Color.parseColor("#22C55E"), Color.parseColor("#EF4444"),
        Color.parseColor("#EF4444"), Color.parseColor("#22C55E")
    )
    private val ledEmoji = arrayOf("🟢", "🔴", "🔴", "🟢")
    private val ledPins = arrayOf("D2", "D3", "D4", "D5")
    private val offColor = Color.parseColor("#374151")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val driver = pendingDriver
                    pendingDriver = null
                    if (granted && driver != null) {
                        openPort(driver)
                    } else {
                        showError("USB permission was denied. Tap CONNECT USB and press OK/Allow.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (port != null && (dev == null || dev == connectedDevice)) {
                        disconnect()
                        showError("Arduino was unplugged.")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        content = findViewById(R.id.content)
        usbDot = findViewById(R.id.usbDot)
        statusText = findViewById(R.id.statusText)
        errorText = findViewById(R.id.errorText)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        speedLabel = findViewById(R.id.speedLabel)
        speedBar = findViewById(R.id.speedBar)
        modeText = findViewById(R.id.modeText)
        effectsGrid = findViewById(R.id.effectsGrid)
        btnEffectsHeader = findViewById(R.id.btnEffectsHeader)
        logText = findViewById(R.id.logText)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        buildLedCards()
        buildEffectButtons()

        btnConnect.setOnClickListener { it.press(); connect() }
        btnDisconnect.setOnClickListener { it.press(); disconnect() }
        bindCommand(R.id.btnAllOn, "ALLON")
        bindCommand(R.id.btnAllOff, "ALLOFF")
        bindCommand(R.id.btnAuto, "AUTO")
        bindCommand(R.id.btnStop, "STOP")

        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                speedLabel.text = "Speed: ${progress + 50} ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                sendCommand("SPEED${sb.progress + 50}")
            }
        })

        btnEffectsHeader.setOnClickListener {
            it.press()
            TransitionManager.beginDelayedTransition(content)
            val open = effectsGrid.visibility != View.VISIBLE
            effectsGrid.visibility = if (open) View.VISIBLE else View.GONE
            btnEffectsHeader.text = if (open) "✨ 50 LIGHT EFFECTS  ▴" else "✨ 50 LIGHT EFFECTS  ▾"
        }

        updateUi()
        // If the app was launched by plugging in the Arduino, connect right away.
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && port == null) connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        disconnect()
        writer.shutdown()
    }

    // ---------- UI building ----------

    private fun buildLedCards() {
        val container = findViewById<LinearLayout>(R.id.ledContainer)
        val inflater = LayoutInflater.from(this)
        for (i in 0 until 4) {
            val n = i + 1
            val card = inflater.inflate(R.layout.item_led, container, false)
            ledDots[i] = card.findViewById(R.id.ledDot)
            setDot(ledDots[i]!!, offColor)
            card.findViewById<TextView>(R.id.ledName).text = "LED $n  •  ${ledPins[i]}"
            val on = card.findViewById<MaterialButton>(R.id.btnOn)
            val off = card.findViewById<MaterialButton>(R.id.btnOff)
            on.text = "${ledEmoji[i]} LED $n ON"
            off.text = "⚫ LED $n OFF"
            on.backgroundTintList = ColorStateList.valueOf(ledOnColors[i])
            on.setOnClickListener { it.press(); sendCommand("L${n}ON") }
            off.setOnClickListener { it.press(); sendCommand("L${n}OFF") }
            container.addView(card)
        }
    }

    private fun buildEffectButtons() {
        for (n in 1..50) {
            val b = MaterialButton(this)
            b.text = "Effect $n"
            b.isAllCaps = false
            b.textSize = 14f
            b.insetTop = 0
            b.insetBottom = 0
            b.minHeight = dp(52)
            b.cornerRadius = dp(14)
            b.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1F2937"))
            b.setOnClickListener { it.press(); sendCommand("EFFECT$n") }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            lp.width = 0
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            effectsGrid.addView(b, lp)
        }
    }

    private fun bindCommand(id: Int, cmd: String) {
        findViewById<View>(id).setOnClickListener { it.press(); sendCommand(cmd) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun View.press() {
        animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
    }

    private fun setDot(v: View, color: Int) {
        (v.background.mutate() as GradientDrawable).setColor(color)
    }

    private fun updateUi() {
        val connected = port != null
        btnConnect.isEnabled = !connected
        btnDisconnect.isEnabled = connected
        btnConnect.alpha = if (connected) 0.4f else 1f
        btnDisconnect.alpha = if (connected) 1f else 0.4f
        when {
            connected && ready -> {
                statusText.text = "USB: Connected ✓"
                setDot(usbDot, Color.parseColor("#22C55E"))
            }
            connected -> {
                statusText.text = "USB: Connecting…"
                setDot(usbDot, Color.parseColor("#F59E0B"))
            }
            else -> {
                statusText.text = "USB: Disconnected"
                setDot(usbDot, offColor)
            }
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            errorText.text = "⚠ $msg"
            errorText.visibility = View.VISIBLE
            log("ERR $msg")
        }
    }

    private fun clearError() {
        errorText.visibility = View.GONE
    }

    private fun log(line: String) {
        logLines.add(line)
        while (logLines.size > 8) logLines.removeAt(0)
        logText.text = logLines.joinToString("\n")
    }

    // ---------- USB ----------

    private fun connect() {
        if (port != null) return
        clearError()
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            showError("No Arduino found. Check the OTG adapter and USB cable, then try again.")
            return
        }
        val driver = drivers[0]
        val dev = driver.device
        log("Found USB device %04X:%04X".format(dev.vendorId, dev.productId))
        if (usbManager.hasPermission(dev)) {
            openPort(driver)
        } else {
            pendingDriver = driver
            val i = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, i, flags)
            usbManager.requestPermission(dev, pi)
        }
    }

    private fun openPort(driver: UsbSerialDriver) {
        val dev = driver.device
        val conn = usbManager.openDevice(dev)
        if (conn == null) {
            showError("Could not open the USB device (permission missing?).")
            return
        }
        val p = driver.ports[0]
        try {
            p.open(conn)
            p.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.setDTR(true); p.setRTS(true) } catch (_: Exception) {}
        } catch (e: Exception) {
            try { p.close() } catch (_: Exception) {}
            showError("Failed to open serial port: ${e.message}")
            return
        }
        port = p
        connectedDevice = dev
        ready = false
        val mgr = SerialInputOutputManager(p, this)
        ioManager = mgr
        mgr.start()
        updateUi()
        log("Port open, $BAUD baud. Arduino is resetting…")
        // Opening the port resets an UNO; wait for the bootloader to finish.
        handler.postDelayed({
            if (port === p) {
                ready = true
                updateUi()
                log("Ready")
            }
        }, BOOT_DELAY_MS)
    }

    private fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        ready = false
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        connectedDevice = null
        for (d in ledDots) d?.let { setDot(it, offColor) }
        modeText.text = "Mode: manual"
        updateUi()
    }

    private fun sendCommand(cmd: String) {
        val p = port
        if (p == null) {
            showError("Not connected. Tap CONNECT USB first.")
            return
        }
        if (!ready) {
            showError("Arduino is still starting, try again in a second.")
            return
        }
        clearError()
        updateLocalState(cmd)
        writer.execute {
            try {
                p.write((cmd + "\n").toByteArray(Charsets.US_ASCII), 1000)
                runOnUiThread { log("TX $cmd") }
            } catch (e: Exception) {
                runOnUiThread {
                    showError("Send failed: ${e.message}")
                    disconnect()
                }
            }
        }
    }

    private fun updateLocalState(cmd: String) {
        val led = Regex("^L([1-4])(ON|OFF)$").find(cmd)
        when {
            led != null -> {
                val i = led.groupValues[1].toInt() - 1
                setDot(ledDots[i]!!, if (led.groupValues[2] == "ON") ledOnColors[i] else offColor)
                modeText.text = "Mode: manual"
            }
            cmd == "ALLON" -> {
                for (i in 0 until 4) setDot(ledDots[i]!!, ledOnColors[i])
                modeText.text = "Mode: manual"
            }
            cmd == "ALLOFF" -> {
                for (i in 0 until 4) setDot(ledDots[i]!!, offColor)
                modeText.text = "Mode: manual"
            }
            cmd == "AUTO" -> modeText.text = "Mode: AUTO"
            cmd == "STOP" -> modeText.text = "Mode: stopped"
            cmd.startsWith("EFFECT") -> modeText.text = "Mode: ${cmd.replace("EFFECT", "Effect ")}"
        }
    }

    // ---------- SerialInputOutputManager.Listener ----------

    override fun onNewData(data: ByteArray) {
        runOnUiThread {
            rxBuffer.append(String(data, Charsets.US_ASCII))
            var idx = rxBuffer.indexOf("\n")
            while (idx >= 0) {
                val line = rxBuffer.substring(0, idx).trim()
                rxBuffer.delete(0, idx + 1)
                if (line.isNotEmpty()) log("RX $line")
                idx = rxBuffer.indexOf("\n")
            }
            if (rxBuffer.length > 500) rxBuffer.clear()
        }
    }

    override fun onRunError(e: Exception) {
        runOnUiThread {
            if (port != null) {
                disconnect()
                showError("USB connection lost: ${e.message}")
            }
        }
    }
}
