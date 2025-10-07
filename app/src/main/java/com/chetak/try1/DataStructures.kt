package com.chetak.try1

// This class is no longer used since TLV 301 is a Point Cloud, not a Target List.
// You can safely delete it or comment it out.
/*
data class Target(
    val tid: Int,
    var posX: Float,
    var posY: Float,
    var posZ: Float,
    var velX: Float,
    var velY: Float,
    var velZ: Float,
    var accX: Float,
    var accY: Float,
    var accZ: Float
)
*/

// Use 'var' because we will create the point first, then add SNR/Noise later.
data class RadarPoint(
    var x: Float, var y: Float, var z: Float, var doppler: Float,
    var snr: Float = 0.0f,
    var noise: Float = 0.0f
)

// Holds statistics from TLV type 306
data class RadarStats(
    val interFrameProcTime: Int,
    val transmitOutTime: Int,
    val tempRx: Short,
    val tempTx: Short,
    val tempPM: Short,
    val tempDIG: Short
)

// Main container for all data parsed from a single frame
data class ParsedFrameData(
    val frameNum: Int,
    val numDetectedObj: Int,
    var points: MutableList<RadarPoint> = mutableListOf(),
    var rangeProfile: List<Int> = listOf(),
    var stats: RadarStats? = null, // Can be null if the TLV isn't present
    var parseError: Int = 0
)


data class FrameHeader(
    val magicWord: Long = 0L,
    val version: Int = 0,
    val totalPacketLen: Int = 0,
    val platform: Int = 0,
    val frameNum: Int = 0,
    val timeCpuCycles: Int = 0,
    val numDetectedObj: Int = 0,
    val numTLVs: Int = 0,
    val subFrameNum: Int = 0
)