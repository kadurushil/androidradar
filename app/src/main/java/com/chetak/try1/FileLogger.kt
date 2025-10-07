package com.chetak.try1

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLogger(context: Context) {
    private var writer: FileWriter? = null

    init {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logFileName = "RadarLog_$timeStamp.log"
            val logFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                logFileName
            )

            writer = FileWriter(logFile, true) // 'true' for appending
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun log(message: String) {
        try {
            writer?.append("${System.currentTimeMillis()}: $message\n")
            writer?.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun close() {
        try {
            writer?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}