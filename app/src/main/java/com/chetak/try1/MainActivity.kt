package com.chetak.try1

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    // ... rest of your variables

    private val tlvParserMap: Map<Int, (ByteBuffer, Int, ParsedFrameData) -> Unit> = mapOf(
        MMWDEMO_OUTPUT_EXT_MSG_DETECTED_POINTS to ::parsePointCloudExtTLV,
        MMWDEMO_OUTPUT_EXT_MSG_RANGE_PROFILE_MAJOR to ::parseRangeProfileTLV,
        MMWDEMO_OUTPUT_MSG_EXT_STATS to ::parseStatsTLV
    )

    private enum class ParsingState { WAITING_FOR_MAGIC, READING_HEADER, READING_PAYLOAD }
    private var currentParsingState = ParsingState.WAITING_FOR_MAGIC
    private val dataBuffer = ByteArrayOutputStream()
    private var expectedPacketLength = 0

    private lateinit var parsedDataText: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var refreshButton: Button
    private lateinit var openPortButton: Button
    private lateinit var configureButton: Button
    private lateinit var closePortButton: Button
    private lateinit var consoleText: TextView
    private lateinit var consoleScrollView: ScrollView

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

        parsedDataText = findViewById(R.id.parsedDataText)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        refreshButton = findViewById(R.id.refreshButton)
        openPortButton = findViewById(R.id.openPortButton)
        configureButton = findViewById(R.id.configureButton)
        closePortButton = findViewById(R.id.closePortButton)
        consoleText = findViewById(R.id.consoleText)
        consoleScrollView = findViewById(R.id.consoleScrollView)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        refreshButton.setOnClickListener { refreshDeviceList() }
        openPortButton.setOnClickListener { openPort() }
        configureButton.setOnClickListener { configureBoard() }
        closePortButton.setOnClickListener { closePort() }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(usbPermissionReceiver, filter)
            }
        }

        refreshDeviceList()
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

/*
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

        // --- Start Log Entry ---
        val logBuilder = StringBuilder()
        logBuilder.append("=====================================================\n")
        logBuilder.append("Frame: ${header.frameNum}, Detected Objects: ${header.numDetectedObj}, TLVs: ${header.numTLVs}\n")
        val hexString = frameBytes.joinToString(" ") { "%02X".format(it) }
        logBuilder.append("Raw Frame (${frameBytes.size} bytes): $hexString\n")
        logBuilder.append("--- TLV Data ---\n")

        // --- NEW DEBUGGING LOGIC ---
        var pointCloudTlvFound = false
        var sideInfoTlvFound = false
        val receivedTlvTypes = mutableListOf<Int>()

        // First pass to identify all TLV types present in the frame
        val tempBuffer = payloadBuffer.asReadOnlyBuffer()
        for (i in 0 until header.numTLVs) {
            if (tempBuffer.remaining() < 8) break
            val tlvType = tempBuffer.int
            val tlvLength = tempBuffer.int
            receivedTlvTypes.add(tlvType)
            if (tlvType == MMWDEMO_OUTPUT_MSG_DETECTED_POINTS) pointCloudTlvFound = true
            if (tlvType == MMWDEMO_OUTPUT_MSG_DETECTED_POINTS_SIDE_INFO) sideInfoTlvFound = true
            if (tempBuffer.remaining() >= tlvLength) {
                tempBuffer.position(tempBuffer.position() + tlvLength)
            } else {
                break
            }
        }
        val debugMsg = "Point Cloud TLV (Type 1) Found: $pointCloudTlvFound, Side Info TLV (Type 7) Found: $sideInfoTlvFound"
        appendToConsole("  -> $debugMsg")
        logBuilder.append("  $debugMsg\n")
        logBuilder.append("  Received TLV Types in this frame: $receivedTlvTypes\n")


        // --- Main Parsing Loop ---
        for (i in 0 until header.numTLVs) {
            if (payloadBuffer.remaining() < 8) {
                frameData.parseError = 2
                break
            }
            val tlvType = payloadBuffer.int
            val tlvLength = payloadBuffer.int

            val parser = tlvParserMap[tlvType]

            if (parser != null) {
                val tlvPayload = payloadBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                tlvPayload.limit(tlvLength)
                parser(tlvPayload, tlvLength, frameData)
            }

            if (payloadBuffer.remaining() >= tlvLength) {
                payloadBuffer.position(payloadBuffer.position() + tlvLength)
            } else {
                frameData.parseError = 3
                break
            }

        }

        // --- Log Parsed Data ---
        if (frameData.targets.isNotEmpty()) {
            logBuilder.append("--- Parsed Targets (${frameData.targets.size}) ---\n")
            frameData.targets.forEach { target ->
                logBuilder.append(
                    String.format(
                        "  ID: %-4d | Pos(X,Y,Z): (%6.2f, %6.2f, %6.2f) | Vel(X,Y,Z): (%6.2f, %6.2f, %6.2f)\n",
                        target.tid, target.posX, target.posY, target.posZ, target.velX, target.velY, target.velZ
                    )
                )
            }
        }
        if (frameData.points.isNotEmpty()) {
            logBuilder.append("--- Parsed Points (${frameData.points.size}) ---\n")
            frameData.points.forEach { point ->
                logBuilder.append(
                    String.format(
                        "  Pos(X,Y,Z): (%6.2f, %6.2f, %6.2f) | Doppler: %6.2f | SNR: %4.1f | Noise: %4.1f\n",
                        point.x, point.y, point.z, point.doppler, point.snr, point.noise
                    )
                )
            }
        }
        logBuilder.append("=====================================================\n")

        // Write the complete entry to the file
        fileLogger.log(logBuilder.toString())

        // Update the UI as before
        updateParsedDataUI(frameData)
    }
*/
    // In try1/MainActivity.kt

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

        // Loop through the number of TLVs specified in the frame header
        for (i in 0 until header.numTLVs) {
            // 1. Check if there's enough data for a TLV header (Type + Length = 8 bytes)
            if (payloadBuffer.remaining() < 8) {
                logBuilder.append("  [Warning] Not enough data for next TLV header. Stopping parse.\n")
                frameData.parseError = 2 // Set an error code
                break
            }

            // 2. Read the TLV header
            val tlvType = payloadBuffer.int
            val tlvLength = payloadBuffer.int // This is the length of the VALUE part

            logBuilder.append("  [TLV #$i] Type: $tlvType, Length: $tlvLength\n")

            // 3. Find the correct parser function for this TLV type
            val parser = tlvParserMap[tlvType]

            // 4. Check if we have enough data for the payload itself
            if (payloadBuffer.remaining() < tlvLength) {
                logBuilder.append("  [Error] Stated TLV length ($tlvLength) exceeds remaining buffer size (${payloadBuffer.remaining()}).\n")
                frameData.parseError = 3 // Set an error code
                break // Stop parsing this frame
            }

            // 5. If a parser exists, use it
            if (parser != null) {
                // Create a 'slice' of the buffer for the parser function.
                // This prevents the parser from accidentally reading too far.
                val tlvPayload = payloadBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                tlvPayload.limit(tlvLength)

                // Execute the parsing function (e.g., parsePointCloudExtTLV)
                parser(tlvPayload, tlvLength, frameData)
            }

            // 6. *** THE CRITICAL FIX ***
            // Advance the buffer's position past this TLV's payload.
            // This is the logic directly ported from the Python script's `offset += total_tlv_length`.
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
    /*private fun processFrame(frameBytes: ByteArray) {
        val header = parseHeader(frameBytes.copyOfRange(0, 40))
        if (header == null) {
            logToConsole("!! PARSER ERROR: Failed to parse frame header.", isError = true)
            fileLogger.log("!! PARSER ERROR: Failed to parse frame header.")
            return
        }

        val frameData = ParsedFrameData(header.frameNum, header.numDetectedObj)
        val payloadBytes = frameBytes.copyOfRange(40, frameBytes.size)
        val payloadBuffer = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)

        // --- Start Log Entry ---
        val logBuilder = StringBuilder()
        logBuilder.append("=====================================================\n")
        logBuilder.append("Frame: ${header.frameNum}, Detected Objects: ${header.numDetectedObj}, TLVs: ${header.numTLVs}\n")
        val hexString = frameBytes.joinToString(" ") { "%02X".format(it) }
        logBuilder.append("Raw Frame (${frameBytes.size} bytes): $hexString\n")
        logBuilder.append("--- TLV Data ---\n")

        for (i in 0 until header.numTLVs) {
            if (payloadBuffer.remaining() < 8) {
                frameData.parseError = 2
                break
            }
            val tlvType = payloadBuffer.int
            val tlvLength = payloadBuffer.int

            val parser = tlvParserMap[tlvType]

            if (parser != null) {
                val tlvPayload = payloadBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                tlvPayload.limit(tlvLength)
                parser(tlvPayload, tlvLength, frameData)
            } else {
                logBuilder.append("  [TLV #$i] Type: $tlvType (Skipped)\n")
            }
            payloadBuffer.position(payloadBuffer.position() + tlvLength)
        }

        // --- Log Parsed Data ---
        if (frameData.targets.isNotEmpty()) {
            logBuilder.append("--- Parsed Targets (${frameData.targets.size}) ---\n")
            frameData.targets.forEach { target ->
                logBuilder.append(
                    String.format(
                        "  ID: %-4d | Pos(X,Y,Z): (%6.2f, %6.2f, %6.2f) | Vel(X,Y,Z): (%6.2f, %6.2f, %6.2f)\n",
                        target.tid, target.posX, target.posY, target.posZ, target.velX, target.velY, target.velZ
                    )
                )
            }
        }
        if (frameData.points.isNotEmpty()) {
            logBuilder.append("--- Parsed Points (${frameData.points.size}) ---\n")
            frameData.points.forEach { point ->
                logBuilder.append(
                    String.format(
                        "  Pos(X,Y,Z): (%6.2f, %6.2f, %6.2f) | Doppler: %6.2f | SNR: %4.1f | Noise: %4.1f\n",
                        point.x, point.y, point.z, point.doppler, point.snr, point.noise
                    )
                )
            }
        }
        logBuilder.append("=====================================================\n")

        // Write the complete entry to the file
        fileLogger.log(logBuilder.toString())

        // Update the UI as before
        updateParsedDataUI(frameData)
    }*/

/*
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

        // --- Start Log Entry ---
        val logBuilder = StringBuilder()
        logBuilder.append("=====================================================\n")
        logBuilder.append("Frame: ${header.frameNum}, Detected Objects: ${header.numDetectedObj}, TLVs: ${header.numTLVs}\n")

        // --- NEW DEBUGGING: Check for Point Cloud TLVs ---
        var pointCloudTlvFound = false
        var sideInfoTlvFound = false

        for (i in 0 until header.numTLVs) {
            if (payloadBuffer.remaining() < 8) { break }
            val tlvType = payloadBuffer.int
            val tlvLength = payloadBuffer.int

            // Check for the specific TLV types we need for point cloud
            if (tlvType == MMWDEMO_OUTPUT_MSG_DETECTED_POINTS) pointCloudTlvFound = true
            if (tlvType == MMWDEMO_OUTPUT_MSG_DETECTED_POINTS_SIDE_INFO) sideInfoTlvFound = true

            val parser = tlvParserMap[tlvType]
            if (parser != null) {
                val tlvPayload = payloadBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                tlvPayload.limit(tlvLength)
                parser(tlvPayload, tlvLength, frameData)
            }
            payloadBuffer.position(payloadBuffer.position() + tlvLength)
        }

        // Log the results of our check to the console and file
        val debugMsg = "Point Cloud TLV (Type 1) Found: $pointCloudTlvFound, Side Info TLV (Type 7) Found: $sideInfoTlvFound"
        appendToConsole("  -> $debugMsg")
        logBuilder.append("  $debugMsg\n")

        // --- Log Parsed Data (rest of the function is the same) ---
        if (frameData.targets.isNotEmpty()) {
            logBuilder.append("--- Parsed Targets (${frameData.targets.size}) ---\n")
            frameData.targets.forEach { target ->
                logBuilder.append(
                    String.format(
                        "  ID: %-4d | Pos(X,Y,Z): (%6.2f, %6.2f, %6.2f)\n",
                        target.tid, target.posX, target.posY, target.posZ
                    )
                )
            }
        }
        logBuilder.append("=====================================================\n")
        fileLogger.log(logBuilder.toString())
        updateParsedDataUI(frameData)
    }
*/

    private fun updateParsedDataUI(frameData: ParsedFrameData) {
        runOnUiThread {
            // Display the number of points detected in this frame.
            parsedDataText.text = "Frame: ${frameData.frameNum}, Points: ${frameData.points.size}"

            // If there are any points, log the details of the first one to the console.
            if (frameData.points.isNotEmpty()) {
                val firstPoint = frameData.points.first()
                val pointString = String.format(
                    "First Point: X: %.2f, Y: %.2f, Z: %.2f, SNR: %.1f",
                    firstPoint.x, firstPoint.y, firstPoint.z, firstPoint.snr
                )
                appendToConsole("   $pointString")
            }
        }
    }
    private fun updateParsedDataUI(frameNumber: Int, points: List<RadarPoint>) {
        runOnUiThread {
            parsedDataText.text = "Frame: $frameNumber, Points: ${points.size}"
            if (points.isNotEmpty()) {
                val firstPoint = points.first()
                val pointString = String.format(
                    "First Point: X: %.2f, Y: %.2f, Z: %.2f, SNR: %.1f",
                    firstPoint.x, firstPoint.y, firstPoint.z, firstPoint.snr
                )
                appendToConsole("   $pointString")
            }
        }
    }

    private fun findMagicWord(data: ByteArray): Int {
        for (i in 0..data.size - UART_MAGIC_WORD.size) {
            val window = data.sliceArray(i until i + UART_MAGIC_WORD.size)
            if (window.contentEquals(UART_MAGIC_WORD)) {
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

    // In MainActivity.kt

    /*private fun configureBoard() {
        if (activePort == null || !activePort!!.isOpen) {
            logToConsole("Cannot configure. Port is not open.", isError = true)
            return
        }

        Thread {
            try {
                // --- Step 1: Clear any stale data from the input buffer ---
                activePort!!.purgeHwBuffers(true, false)
                logToConsole("Input buffer purged.")

                for (command in CONFIG_COMMANDS) {
                    val commandWithTerminator = "$command\n"
                    activePort!!.write(commandWithTerminator.toByteArray(), 500)
                    appendToConsole(">> $command")
                    Thread.sleep(50) // 50ms delay between commands

                    if (command.startsWith("baudRate")) {
                        // --- Step 2: Critical timing for baud rate change ---
                        logToConsole("Baud rate command sent. Pausing for board to reconfigure...")

                        // Wait a bit longer here to give the board time to process the command
                        Thread.sleep(200)

                        try {
                            val newBaudRate = command.split(" ")[1].toInt()
                            logToConsole("Updating app's port to new baud rate: $newBaudRate")
                            activePort!!.setParameters(newBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                            // A final short pause for the port to settle
                            Thread.sleep(100)
                            logToConsole("App's port reconfigured. Ready for high-speed data.")

                        } catch (e: Exception) {
                            logToConsole("Failed to parse or set new baud rate: ${e.message}", isError = true)
                            break
                        }
                    }
                }
                logToConsole("Configuration sequence sent. Listening for data...")
            } catch (e: IOException) {
                logToConsole("Error sending commands: ${e.message}", isError = true)
            }
        }.start()
    }*/
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