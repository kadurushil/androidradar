package com.chetak.try1

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.ScatterChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.ScatterData
import com.github.mikephil.charting.data.ScatterDataSet
import com.github.mikephil.charting.interfaces.datasets.IScatterDataSet
import com.github.mikephil.charting.renderer.ScatterChartRenderer
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private lateinit var fileLogger: FileLogger

    private val tlvParserMap: Map<Int, (ByteBuffer, Int, ParsedFrameData) -> Unit> = mapOf(
        MMWDEMO_OUTPUT_EXT_MSG_DETECTED_POINTS to ::parsePointCloudExtTLV,
        MMWDEMO_OUTPUT_EXT_MSG_RANGE_PROFILE_MAJOR to ::parseRangeProfileTLV,
        MMWDEMO_OUTPUT_MSG_EXT_STATS to ::parseStatsTLV
    )

    private enum class ParsingState { WAITING_FOR_MAGIC, READING_HEADER, READING_PAYLOAD }
    private var currentParsingState = ParsingState.WAITING_FOR_MAGIC
    private val dataBuffer = ByteArrayOutputStream()
    private var expectedPacketLength = 0

    // --- UI Elements ---
    private lateinit var parsedDataText: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var refreshButton: Button
    private lateinit var openPortButton: Button
    private lateinit var configureButton: Button
    private lateinit var closePortButton: Button
    private lateinit var consoleText: TextView
    private lateinit var consoleScrollView: ScrollView
    private lateinit var scatterChart: ScatterChart // <-- NEW: Chart variable

    // --- USB & Serial Port Variables ---
    private lateinit var usbManager: UsbManager
    private var availableDrivers = mutableListOf<UsbSerialDriver>()
    private var selectedDriver: UsbSerialDriver? = null
    private var activePort: UsbSerialPort? = null
    private var portIoManager: SerialInputOutputManager? = null
    private val ACTION_USB_PERMISSION = "com.chetak.try1.USB_PERMISSION"
    private val CLI_PORT_INDEX = 0
    private val INITIAL_CLI_BAUD_RATE = 115200

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    logToConsole("USB permission granted. You may now open the port.")
                } else {
                    logToConsole("USB permission denied.")
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fileLogger = FileLogger(this)

        // Find all UI elements
        parsedDataText = findViewById(R.id.parsedDataText)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        refreshButton = findViewById(R.id.refreshButton)
        openPortButton = findViewById(R.id.openPortButton)
        configureButton = findViewById(R.id.configureButton)
        closePortButton = findViewById(R.id.closePortButton)
        consoleText = findViewById(R.id.consoleText)
        consoleScrollView = findViewById(R.id.consoleScrollView)
        scatterChart = findViewById(R.id.scatterChart) // <-- NEW: Find chart view

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        refreshButton.setOnClickListener { refreshDeviceList() }
        openPortButton.setOnClickListener { openPort() }
        configureButton.setOnClickListener { configureBoard() }
        closePortButton.setOnClickListener { closePort() }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        // Correctly handle receiver registration for different Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }

        setupChart() // <-- NEW: Configure the chart on startup
        refreshDeviceList()
    }

    // --- NEW: Function to configure the scatter plot's appearance ---
    private fun setupChart() {
        scatterChart.description.isEnabled = false
        scatterChart.isDragEnabled = true
        scatterChart.setScaleEnabled(true)
        scatterChart.setPinchZoom(true)
        scatterChart.setDrawGridBackground(true)
        scatterChart.setGridBackgroundColor(Color.DKGRAY)
        scatterChart.legend.isEnabled = false

        // Configure X Axis
        scatterChart.xAxis.textColor = Color.WHITE
        scatterChart.xAxis.gridColor = Color.GRAY
        scatterChart.xAxis.axisLineColor = Color.WHITE
        scatterChart.xAxis.setLabelCount(6, true)
        scatterChart.xAxis.axisMinimum = -5f
        scatterChart.xAxis.axisMaximum = 5f

        // Configure Y Axis
        scatterChart.axisLeft.textColor = Color.WHITE
        scatterChart.axisLeft.gridColor = Color.GRAY
        scatterChart.axisLeft.axisLineColor = Color.WHITE
        scatterChart.axisLeft.setLabelCount(6, true)
        scatterChart.axisLeft.axisMinimum = 0f
        scatterChart.axisLeft.axisMaximum = 10f

        scatterChart.axisRight.isEnabled = false

        // --- NEW LOGIC ---
        // Create an empty data object and set it to the chart once.
        // We will update this object later instead of creating a new one every time.
        scatterChart.data = ScatterData()
    }

    override fun onNewData(data: ByteArray) {
        dataBuffer.write(data)
        while (dataBuffer.size() > 0) {
            when (currentParsingState) {
                ParsingState.WAITING_FOR_MAGIC -> {
                    val bufferBytes = dataBuffer.toByteArray()
                    val magicIndex = findMagicWord(bufferBytes)
                    if (magicIndex != -1) {
                        val bytesToKeep = bufferBytes.copyOfRange(magicIndex, bufferBytes.size)
                        dataBuffer.reset()
                        dataBuffer.write(bytesToKeep)
                        currentParsingState = ParsingState.READING_HEADER
                    } else {
                        if (dataBuffer.size() > 2048) { dataBuffer.reset() }
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
                            Log.e("Parser", "Invalid header detected. Resetting parser.")
                            resetParserState()
                        }
                    } else {
                        return
                    }
                }
                ParsingState.READING_PAYLOAD -> {
                    if (dataBuffer.size() >= expectedPacketLength) {
                        val frameBytes = dataBuffer.toByteArray().copyOfRange(0, expectedPacketLength)
                        processFrame(frameBytes)
                        val remainingBytes = dataBuffer.toByteArray().copyOfRange(expectedPacketLength, dataBuffer.size())
                        dataBuffer.reset()
                        dataBuffer.write(remainingBytes)
                        currentParsingState = ParsingState.WAITING_FOR_MAGIC
                    } else {
                        return
                    }
                }
            }
        }
    }

    override fun onRunError(e: Exception) {
        logToConsole("Communication Error: ${e.message}", isError = true)
        closePort()
    }

    private fun processFrame(frameBytes: ByteArray) {
        val header = parseHeader(frameBytes.copyOfRange(0, 40))
        if (header == null) {
            logToConsole("!! PARSER ERROR: Failed to parse frame header.", isError = true)
            fileLogger.log("!! PARSER ERROR: Failed to parse frame header.")
            return
        }

        val frameData = ParsedFrameData(header.frameNum, header.numDetectedObj)
        val payloadBytes = frameBytes.copyOfRange(40, frameBytes.size)
        val payloadBuffer = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)

        val logBuilder = StringBuilder()
        logBuilder.append("=====================================================\n")
        logBuilder.append("Frame: ${header.frameNum}, Detected Objects: ${header.numDetectedObj}, TLVs: ${header.numTLVs}\n")

        for (i in 0 until header.numTLVs) {
            if (payloadBuffer.remaining() < 8) {
                logBuilder.append("  [Warning] Not enough data for next TLV header. Stopping parse.\n")
                frameData.parseError = 2
                break
            }

            val tlvType = payloadBuffer.int
            val tlvLength = payloadBuffer.int

            logBuilder.append("  [TLV #$i] Type: $tlvType, Length: $tlvLength\n")

            val parser = tlvParserMap[tlvType]

            if (payloadBuffer.remaining() < tlvLength) {
                logBuilder.append("  [Error] Stated TLV length ($tlvLength) exceeds remaining buffer size (${payloadBuffer.remaining()}).\n")
                frameData.parseError = 3
                break
            }

            if (parser != null) {
                val tlvPayload = payloadBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                tlvPayload.limit(tlvLength)
                parser(tlvPayload, tlvLength, frameData)
            }

            payloadBuffer.position(payloadBuffer.position() + tlvLength)
        }

        // --- Log the data you parsed ---
        if (frameData.points.isNotEmpty()) {
            logBuilder.append("--- Parsed Points (${frameData.points.size}) ---\n")
            frameData.points.forEach { point ->
                logBuilder.append(
                    String.format(
                        "  Pos(X,Y,Z): (%6.2f, %6.2f, %6.2f) | Doppler: %6.2f | SNR: %4.1f\n",
                        point.x, point.y, point.z, point.doppler, point.snr
                    )
                )
            }
        }
        logBuilder.append("=====================================================\n")
        fileLogger.log(logBuilder.toString())

        updateParsedDataUI(frameData)
    }

    // --- NEW: Helper function to map SNR to a color ---
    private fun getColorForSNR(snr: Float): Int {
        // Define an SNR range for color mapping (e.g., 0 to 40 dB)
        val minSnr = 0f
        val maxSnr = 40f

        // Normalize SNR to a 0.0 to 1.0 range
        val normalizedSnr = ((snr - minSnr) / (maxSnr - minSnr)).coerceIn(0f, 1f)

        // Interpolate color from Blue (low SNR) to Red (high SNR)
        // As normalizedSnr goes from 0 to 1, red increases and blue decreases.
        val red = (255 * normalizedSnr).toInt()
        val blue = (255 * (1 - normalizedSnr)).toInt()
        val green = 0

        return Color.rgb(red, green, blue)
    }

    // --- UPDATED: This function now updates both the text view and the chart ---
    // In MainActivity.kt
// Replace your existing updateParsedDataUI function with this final version

    // In MainActivity.kt
// Replace your existing updateParsedDataUI function with this one

    private fun updateParsedDataUI(frameData: ParsedFrameData) {
        runOnUiThread {
            parsedDataText.text = "Frame: ${frameData.frameNum}, Points: ${frameData.points.size}"

            // 1) Build entries & colors, filtering invalid numbers.
            val entries = ArrayList<Entry>()
            val colors = ArrayList<Int>()

            for (point in frameData.points) {
                if (point.x.isFinite() && point.y.isFinite()) {
                    entries.add(Entry(point.x, point.y))
                    colors.add(getColorForSNR(point.snr))
                } else {
                    // Optionally log filtered points for debugging:
                    // fileLogger.log("Filtered invalid point: x=${point.x} y=${point.y} snr=${point.snr}")
                }
            }

            // 2) If no valid entries, clear the chart and return early.
            if (entries.isEmpty()) {
                // Clear data on UI thread safely
                scatterChart.data = ScatterData()
                scatterChart.clear()            // clears datasets and renderer state
                scatterChart.invalidate()
                return@runOnUiThread
            }

            // 3) Ensure the color list is at least as long as entries (chart will cycle colors otherwise,
            // but we keep it explicit)
            if (colors.size < entries.size) {
                val defaultColor = Color.WHITE
                while (colors.size < entries.size) colors.add(defaultColor)
            }

            // 4) Create dataset and set defensive properties
            val dataSet = ScatterDataSet(entries, "Detected Points")
            dataSet.setColors(colors)
            dataSet.setScatterShape(ScatterChart.ScatterShape.CIRCLE)
            dataSet.scatterShapeSize = 12f

            // CRITICAL: Disable drawing of value labels to avoid drawValues crash
            dataSet.setDrawValues(false)

            // Optional: disable icons too
            dataSet.setDrawIcons(false)

            // 5) Create a new ScatterData and set it to the chart.
            val scatterData = ScatterData(dataSet)

            // Replace data and notify chart so internal buffers are rebuilt correctly
            scatterChart.data = scatterData
            scatterChart.notifyDataSetChanged()
            scatterChart.invalidate()
        }
    }


    private fun findMagicWord(data: ByteArray): Int {
        val magicWord = UART_MAGIC_WORD
        for (i in 0..data.size - magicWord.size) {
            val window = data.sliceArray(i until i + magicWord.size)
            if (window.contentEquals(magicWord)) {
                return i
            }
        }
        return -1
    }

    private fun parseHeader(headerBytes: ByteArray): FrameHeader? {
        return try {
            val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            FrameHeader(bb.long, bb.int, bb.int, bb.int, bb.int, bb.int, bb.int, bb.int, bb.int)
        } catch (e: Exception) {
            Log.e("parseHeader", "Error parsing header: ${e.message}")
            null
        }
    }

    private fun resetParserState() {
        dataBuffer.reset()
        currentParsingState = ParsingState.WAITING_FOR_MAGIC
        runOnUiThread { scatterChart.clear() } // Clear the chart when resetting
    }

    private fun openPort() {
        val selectedPosition = deviceSpinner.selectedItemPosition
        if (availableDrivers.isEmpty() || selectedPosition < 0) {
            logToConsole("No device selected to open.", isError = true)
            return
        }
        selectedDriver = availableDrivers[selectedPosition]

        if (!usbManager.hasPermission(selectedDriver!!.device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(selectedDriver!!.device, permissionIntent)
            logToConsole("Requesting USB permission...")
            return
        }

        try {
            activePort = selectedDriver!!.ports[CLI_PORT_INDEX]
            activePort!!.open(usbManager.openDevice(selectedDriver!!.device))
            activePort!!.setParameters(INITIAL_CLI_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            portIoManager = SerialInputOutputManager(activePort, this)
            portIoManager!!.start()
            logToConsole("Port opened successfully at $INITIAL_CLI_BAUD_RATE baud.")
        } catch (e: IOException) {
            logToConsole("Error opening port: ${e.message}", isError = true)
            activePort = null
        }
        updateButtonStates()
    }


    private fun configureBoard() {
        if (activePort == null || !activePort!!.isOpen) {
            logToConsole("Cannot configure. Port is not open.", isError = true)
            return
        }
        Thread {
            try {
                for (command in CONFIG_COMMANDS) {
                    val commandWithTerminator = "$command\n"
                    activePort!!.write(commandWithTerminator.toByteArray(), 500)
                    appendToConsole(">> $command")
                    Thread.sleep(100)
                    if (command.startsWith("baudRate")) {
                        try {
                            val newBaudRate = command.split(" ")[1].toInt()
                            logToConsole("Updating app's port to new baud rate: $newBaudRate")
                            activePort!!.setParameters(newBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                            Thread.sleep(100)
                        } catch (e: Exception) {
                            logToConsole("Failed to parse or set new baud rate: ${e.message}", isError = true)
                            break
                        }
                    }
                }
                logToConsole("Configuration sequence sent.")
            } catch (e: IOException) {
                logToConsole("Error sending commands: ${e.message}", isError = true)
            }
        }.start()
    }

    private fun closePort() {
        resetParserState()
        portIoManager?.stop()
        portIoManager = null
        try {
            activePort?.close()
            logToConsole("Port closed.")
        } catch (e: IOException) { /* Ignore */ }
        activePort = null
        updateButtonStates()
        runOnUiThread { parsedDataText.text = "Parsed Data: Waiting..." }
    }

    private fun refreshDeviceList() {
        availableDrivers.clear()
        availableDrivers.addAll(UsbSerialProber.getDefaultProber().findAllDrivers(usbManager))
        val spinnerItems = availableDrivers.map { driver ->
            "VID:${driver.device.vendorId} PID:${driver.device.productId}"
        }.toMutableList()
        if (spinnerItems.isEmpty()) spinnerItems.add("No devices found")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, spinnerItems)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = adapter
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val portIsOpen = activePort?.isOpen ?: false
        openPortButton.isEnabled = !portIsOpen
        configureButton.isEnabled = portIsOpen
        closePortButton.isEnabled = portIsOpen
        refreshButton.isEnabled = !portIsOpen
        deviceSpinner.isEnabled = !portIsOpen
    }

    private fun appendToConsole(message: String, isIncoming: Boolean = false, isError: Boolean = false) {
        runOnUiThread {
            val color = when {
                isError -> ContextCompat.getColor(this, android.R.color.holo_red_light)
                isIncoming -> ContextCompat.getColor(this, android.R.color.holo_green_light)
                else -> ContextCompat.getColor(this, android.R.color.white)
            }
            val spannable = SpannableStringBuilder(message + "\n")
            spannable.setSpan(ForegroundColorSpan(color), 0, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            consoleText.append(spannable)
            consoleScrollView.post { consoleScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun logToConsole(message: String, isError: Boolean = false) {
        runOnUiThread {
            val color = if (isError) ContextCompat.getColor(this, android.R.color.holo_orange_light)
            else ContextCompat.getColor(this, android.R.color.darker_gray)
            val spannable = SpannableStringBuilder("--- $message ---\n")
            spannable.setSpan(ForegroundColorSpan(color), 0, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            consoleText.append(spannable)
            consoleScrollView.post { consoleScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        closePort()
        unregisterReceiver(usbPermissionReceiver)
        fileLogger.close()
        super.onDestroy()
    }
}