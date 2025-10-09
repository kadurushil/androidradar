package com.chetak.try1

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.ScatterChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.ScatterData
import com.github.mikephil.charting.data.ScatterDataSet
import com.github.mikephil.charting.utils.ColorTemplate
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import com.github.mikephil.charting.utils.EntryXComparator

/**
 * MainActivity — final consolidated version that matches activity_main.xml IDs and
 * integrates optional CameraX preview (only if a PreviewView with id "previewView" exists).
 *
 * Uses:
 * - activity_main.xml IDs: deviceSpinner, refreshButton, openPortButton, configureButton,
 *   closePortButton, parsedDataText, scatterChart, consoleScrollView, consoleText. :contentReference[oaicite:9]{index=9}
 * - ParsedFrameData.points as the radar points list. :contentReference[oaicite:10]{index=10}
 * - TLV parser functions from TlvParsers.kt. :contentReference[oaicite:11]{index=11}
 */
class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private val TAG = "MainActivity"

    // --- File logger (your FileLogger.kt) ---
    private lateinit var fileLogger: FileLogger

    // --- TLV parser lookup (your parsing functions) ---
    private val tlvParserMap: Map<Int, (ByteBuffer, Int, ParsedFrameData) -> Unit> = mapOf(
        MMWDEMO_OUTPUT_EXT_MSG_DETECTED_POINTS to ::parsePointCloudExtTLV,
        MMWDEMO_OUTPUT_EXT_MSG_RANGE_PROFILE_MAJOR to ::parseRangeProfileTLV,
        MMWDEMO_OUTPUT_MSG_EXT_STATS to ::parseStatsTLV
    )

    // --- Parsing state machine ---
    private enum class ParsingState { WAITING_FOR_MAGIC, READING_HEADER, READING_PAYLOAD }
    private var currentParsingState = ParsingState.WAITING_FOR_MAGIC
    private val dataBuffer = ByteArrayOutputStream()
    private var expectedPacketLength = 0

    // --- UI references (these IDs match activity_main.xml) ---
    private lateinit var parsedDataText: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var refreshButton: Button
    private lateinit var openPortButton: Button
    private lateinit var configureButton: Button
    private lateinit var closePortButton: Button
    private lateinit var consoleText: TextView
    private lateinit var consoleScrollView: ScrollView
    private lateinit var scatterChart: ScatterChart

    // --- Camera (optional) ---
    private var previewView: PreviewView? = null
    private var cameraExecutor: ExecutorService? = null
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCameraPreview() else runOnUiThread {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    // --- USB / serial ---
    private lateinit var usbManager: UsbManager
    private var availableDrivers = mutableListOf<UsbSerialDriver>()
    private var selectedDriver: UsbSerialDriver? = null
    private var activePort: UsbSerialPort? = null
    private var portIoManager: SerialInputOutputManager? = null
    private val ACTION_USB_PERMISSION = "com.chetak.try1.USB_PERMISSION"
    private val CLI_PORT_INDEX = 0
    private val INITIAL_CLI_BAUD_RATE = 115200

    // USB permission receiver
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                logToConsole(if (ok) "USB permission granted" else "USB permission denied")
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Logger
        fileLogger = FileLogger(this)

        // Find UI elements (IDs are from your layout) :contentReference[oaicite:12]{index=12}
        parsedDataText = findViewById(R.id.parsedDataText)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        refreshButton = findViewById(R.id.refreshButton)
        openPortButton = findViewById(R.id.openPortButton)
        configureButton = findViewById(R.id.configureButton)
        closePortButton = findViewById(R.id.closePortButton)
        consoleText = findViewById(R.id.consoleText)
        consoleScrollView = findViewById(R.id.consoleScrollView)
        scatterChart = findViewById(R.id.scatterChart)

        // USB manager
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Wire buttons
        refreshButton.setOnClickListener { refreshDeviceList() }
        openPortButton.setOnClickListener { openPort() }
        configureButton.setOnClickListener { configureBoard() }
        closePortButton.setOnClickListener { closePort() }

        // Register USB permission receiver
        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)

        // Chart init
        setupChart()
        refreshDeviceList()

        // Camera is optional: start only if previewView exists in layout
        val previewId = resources.getIdentifier("previewView", "id", packageName)
        if (previewId != 0) {
            previewView = findViewById(previewId)
            cameraExecutor = Executors.newSingleThreadExecutor()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCameraPreview()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        try { fileLogger.close() } catch (_: Exception) {}
        try { portIoManager?.stop() } catch (_: Exception) {}
        try { activePort?.close() } catch (_: Exception) {}
        cameraExecutor?.shutdown()
    }

    // ---------------- CameraX preview (optional) ----------------
    private fun startCameraPreview() {
        val pv = previewView ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- Chart setup & update ----------------
    // MainActivity.kt

    private fun setupChart() {
        scatterChart.description.isEnabled = false
        scatterChart.setTouchEnabled(true)
        scatterChart.setPinchZoom(true)
        scatterChart.setNoDataText("Waiting for radar points...")
        scatterChart.legend.isEnabled = false

        // --- Configure X-Axis (Horizontal) ---
        scatterChart.xAxis.textColor = Color.WHITE
        scatterChart.xAxis.axisMinimum = -5f
        scatterChart.xAxis.axisMaximum = 5f

        // --- Configure Y-Axis (Vertical) ---
        scatterChart.axisLeft.textColor = Color.WHITE
        scatterChart.axisLeft.axisMinimum = 0f
        scatterChart.axisLeft.axisMaximum = 10f
        scatterChart.axisRight.isEnabled = false // Keep the right axis disabled

        // Initialize with empty data
        scatterChart.data = ScatterData()
    }

    private fun updateParsedDataUI(frameData: ParsedFrameData) {
        runOnUiThread {
            val validEntries = ArrayList<Entry>()
            for (p in frameData.points) {
                val isXValid = p.x.isFinite() && p.x >= -5f && p.x <= 5f
                val isYValid = p.y.isFinite() && p.y >= 0f && p.y <= 10f
                if (isXValid && isYValid) {
                    validEntries.add(Entry(p.x, p.y))
                }
            }

            parsedDataText.text = "Frame: ${frameData.frameNum}, Points: ${frameData.points.size} (Plotting: ${validEntries.size})"
            validEntries.sortWith(EntryXComparator())

            // --- INTENSIVE DIAGNOSTIC LOGGING ---
            // This will run every frame so we can see the state right before a crash.
            Log.d("ChartState", "--- Chart Update --- Plotting ${validEntries.size} points.")
            if (validEntries.isNotEmpty()) {
                val yMin = validEntries.minByOrNull { it.y }?.y
                val yMax = validEntries.maxByOrNull { it.y }?.y
                Log.d("ChartState", "Data Range: X from ${validEntries.first().x} to ${validEntries.last().x}, Y from $yMin to $yMax")
            }
            if (scatterChart.data != null && scatterChart.data.entryCount > 0) {
                Log.d("ChartState", "Chart Internals: xMin=${scatterChart.xChartMin}, xMax=${scatterChart.xChartMax}, yMin=${scatterChart.yMin}, yMax=${scatterChart.yMax}")
            }
            // --- END LOGGING ---

            try {
                val data = scatterChart.data
                if (validEntries.isEmpty()) {
                    // --- FINAL FIX: GENTLER DATA CLEARING ---
                    // Instead of scatterChart.clear(), we update the dataset with an empty list.
                    if (data != null && data.dataSetCount > 0) {
                        val dataSet = data.getDataSetByIndex(0) as ScatterDataSet
                        dataSet.values = emptyList() // More stable than clear()
                        data.notifyDataChanged()
                        scatterChart.notifyDataSetChanged()
                    }
                } else {
                    if (data != null && data.dataSetCount > 0) {
                        val dataSet = data.getDataSetByIndex(0) as ScatterDataSet
                        dataSet.values = validEntries
                        data.notifyDataChanged()
                        scatterChart.notifyDataSetChanged()
                    } else {
                        val dataSet = ScatterDataSet(validEntries, "Detected Points")
                        dataSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
                        dataSet.setDrawValues(false)
                        dataSet.scatterShapeSize = 10f
                        val scatterData = ScatterData(dataSet)
                        scatterChart.data = scatterData
                    }
                }
                scatterChart.invalidate()
            } catch (e: Exception) {
                // This will likely not catch the crash, but we leave it as a final safeguard.
                Log.e("ChartState", "Caught exception during data setup: ", e)
            }
        }
    }

    private fun getColorForSNR(snr: Float): Int {
        val minSnr = 0f
        val maxSnr = 40f
        val t = ((snr - minSnr) / (maxSnr - minSnr)).coerceIn(0f, 1f)
        val red = (255 * t).roundToInt().coerceIn(0, 255)
        val green = (255 * (1 - t)).roundToInt().coerceIn(0, 255)
        return Color.rgb(red, green, 0)
    }

    // ---------------- SerialInputOutputManager.Listener ----------------
    override fun onNewData(data: ByteArray) {
        // Append and try to parse frames using state machine
        dataBuffer.write(data)
        while (dataBuffer.size() > 0) {
            when (currentParsingState) {
                ParsingState.WAITING_FOR_MAGIC -> {
                    val bytes = dataBuffer.toByteArray()
                    val magicIndex = findMagicWord(bytes)
                    if (magicIndex != -1) {
                        val keep = bytes.copyOfRange(magicIndex, bytes.size)
                        dataBuffer.reset()
                        dataBuffer.write(keep)
                        currentParsingState = ParsingState.READING_HEADER
                    } else {
                        if (dataBuffer.size() > 4096) dataBuffer.reset()
                        return
                    }
                }
                ParsingState.READING_HEADER -> {
                    if (dataBuffer.size() >= 40) {
                        val headerBytes = dataBuffer.toByteArray().copyOfRange(0, 40)
                        val header = parseHeader(headerBytes)
                        if (header != null && header.totalPacketLen > 40 && header.totalPacketLen < 10000) {
                            expectedPacketLength = header.totalPacketLen
                            currentParsingState = ParsingState.READING_PAYLOAD
                        } else {
                            resetParserState()
                        }
                    } else return
                }
                ParsingState.READING_PAYLOAD -> {
                    if (dataBuffer.size() >= expectedPacketLength) {
                        val frame = dataBuffer.toByteArray().copyOfRange(0, expectedPacketLength)
                        processFrame(frame)
                        val remaining = dataBuffer.toByteArray().copyOfRange(expectedPacketLength, dataBuffer.size())
                        dataBuffer.reset()
                        dataBuffer.write(remaining)
                        currentParsingState = ParsingState.WAITING_FOR_MAGIC
                    } else return
                }
            }
        }
    }

    override fun onRunError(e: Exception) {
        e.printStackTrace()
        runOnUiThread {
            Toast.makeText(this@MainActivity, "Serial IO error: ${e.message}", Toast.LENGTH_LONG).show()
            try { closePort() } catch (_: Exception) {}
            updateButtonStates()
        }
    }

    // ---------------- Frame parsing ----------------
    private fun processFrame(frameBytes: ByteArray) {
        val header = parseHeader(frameBytes.copyOfRange(0, 40))
        if (header == null) {
            logToConsole("Parser error: invalid header", isError = true)
            fileLogger.log("Parser error: invalid header")
            return
        }

        val frameData = ParsedFrameData(header.frameNum, header.numDetectedObj)
        val payloadBytes = frameBytes.copyOfRange(40, frameBytes.size)
        val payloadBuffer = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)

        val sb = StringBuilder()
        sb.append("=== Frame ${header.frameNum} (${header.numDetectedObj} objs) ===\n")

        for (i in 0 until header.numTLVs) {
            if (payloadBuffer.remaining() < 8) {
                sb.append(" Insufficient TLV header\n")
                frameData.parseError = 2
                break
            }
            val tlvType = payloadBuffer.int
            val tlvLen = payloadBuffer.int
            sb.append(" TLV #$i type=$tlvType len=$tlvLen\n")
            if (payloadBuffer.remaining() < tlvLen) {
                sb.append("  TLV exceeds remaining buffer\n")
                frameData.parseError = 3
                break
            }
            val parser = tlvParserMap[tlvType]
            if (parser != null) {
                val tlvSlice = payloadBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                tlvSlice.limit(tlvLen)
                parser(tlvSlice, tlvLen, frameData)
            }
            payloadBuffer.position(payloadBuffer.position() + tlvLen)
        }

        if (frameData.points.isNotEmpty()) {
            frameData.points.take(10).forEach { p ->
                sb.append(String.format("  (%.2f, %.2f, %.2f) doppler=%.2f snr=%.1f\n", p.x, p.y, p.z, p.doppler, p.snr))
            }
        }

        fileLogger.log(sb.toString())
        updateParsedDataUI(frameData)
    }

    // ---------------- Helpers ----------------
    private fun findMagicWord(data: ByteArray): Int {
        val magic = UART_MAGIC_WORD
        for (i in 0..data.size - magic.size) {
            if (data.sliceArray(i until i + magic.size).contentEquals(magic)) return i
        }
        return -1
    }

    private fun parseHeader(headerBytes: ByteArray): FrameHeader? {
        return try {
            val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            FrameHeader(bb.long, bb.int, bb.int, bb.int, bb.int, bb.int, bb.int, bb.int, bb.int)
        } catch (e: Exception) {
            Log.e(TAG, "parseHeader error: ${e.message}", e)
            null
        }
    }

    private fun resetParserState() {
        dataBuffer.reset()
        currentParsingState = ParsingState.WAITING_FOR_MAGIC
        runOnUiThread { scatterChart.clear() }
    }

    // ---------------- USB / Port controls ----------------
    private fun refreshDeviceList() {
        availableDrivers.clear()
        try {
            availableDrivers.addAll(UsbSerialProber.getDefaultProber().findAllDrivers(usbManager))
        } catch (e: Exception) {
            Log.w(TAG, "USB probe exception: ${e.message}")
        }
        val items = availableDrivers.map { d -> "VID:${d.device.vendorId} PID:${d.device.productId}" }.toMutableList()
        if (items.isEmpty()) items.add("No devices found")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = adapter
        updateButtonStates()
    }

    private fun openPort() {
        val pos = deviceSpinner.selectedItemPosition
        if (availableDrivers.isEmpty() || pos < 0) {
            logToConsole("No device selected", isError = true)
            return
        }
        selectedDriver = availableDrivers[pos]
        val device = selectedDriver!!.device
        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, permissionIntent)
            logToConsole("Requested USB permission")
            return
        }
        try {
            activePort = selectedDriver!!.ports[CLI_PORT_INDEX]
            val conn = usbManager.openDevice(device)
            activePort!!.open(conn)
            activePort!!.setParameters(INITIAL_CLI_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            portIoManager = SerialInputOutputManager(activePort, this)
            portIoManager!!.start()
            logToConsole("Port opened at $INITIAL_CLI_BAUD_RATE")
        } catch (e: IOException) {
            logToConsole("Open port error: ${e.message}", isError = true)
            activePort = null
        }
        updateButtonStates()
    }

    private fun configureBoard() {
        if (activePort == null) {
            logToConsole("Port not open", isError = true)
            return
        }
        Thread {
            try {
                for (cmd in CONFIG_COMMANDS) {
                    val full = "$cmd\n"
                    activePort!!.write(full.toByteArray(), 500)
                    fileLogger.log("-> $cmd")
                    Thread.sleep(100)
                    if (cmd.startsWith("baudRate")) {
                        try {
                            val newBaud = cmd.split(" ")[1].toInt()
                            activePort!!.setParameters(newBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                            Thread.sleep(100)
                        } catch (e: Exception) {
                            logToConsole("Baud parse/set failed: ${e.message}", isError = true)
                        }
                    }
                }
                logToConsole("Config commands sent")
            } catch (e: IOException) {
                logToConsole("Config send error: ${e.message}", isError = true)
            }
        }.start()
    }

    private fun closePort() {
        try { portIoManager?.stop(); portIoManager = null } catch (_: Exception) {}
        try { activePort?.close(); activePort = null } catch (_: Exception) {}
        runOnUiThread {
            updateButtonStates()
            parsedDataText.text = "Port closed"
            logToConsole("Port closed")
        }
    }

    private fun updateButtonStates() {
        runOnUiThread {
            val connected = (activePort != null)
            openPortButton.isEnabled = !connected
            closePortButton.isEnabled = connected
            configureButton.isEnabled = connected
        }
    }

    // ---------------- Console logging helpers ----------------
    private fun logToConsole(message: String, isError: Boolean = false) {
        runOnUiThread {
            val color = if (isError) android.R.color.holo_orange_light else android.R.color.darker_gray
            consoleText.append("--- $message ---\n")
            consoleScrollView.post { consoleScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
