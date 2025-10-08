package com.chetak.try1

import java.nio.ByteBuffer

/**
 * Parses the Extended Point Cloud TLV (Type 301).
 * Translated from parsePointCloudExtTLV in parseTLVs.py.
 */
fun parsePointCloudExtTLV(payload: ByteBuffer, tlvLength: Int, frameData: ParsedFrameData) {
    if (tlvLength < SIZEOF_POINT_UNIT_STRUCT) return

    // 1. Read the Point Unit structure which contains decompression values
    val xyzUnit = payload.float
    val dopplerUnit = payload.float
    val snrUnit = payload.float
    val noiseUnit = payload.float
    // Skip numDetPointsMajor and numDetPointsMinor (2 shorts = 4 bytes)
    payload.position(payload.position() + 4)

    // 2. Calculate the number of points
    val numPoints = (tlvLength - SIZEOF_POINT_UNIT_STRUCT) / SIZEOF_DETECTED_POINT_EXT_STRUCT

    // 3. Loop through and parse each point
    for (i in 0 until numPoints) {
        if (payload.remaining() < SIZEOF_DETECTED_POINT_EXT_STRUCT) break

        // Read the raw compressed values (shorts and bytes)
        val xRaw = payload.short
        val yRaw = payload.short
        val zRaw = payload.short
        val dopplerRaw = payload.short
        val snrRaw = payload.get().toUByte().toInt()
        val noiseRaw = payload.get().toUByte().toInt()

        // Decompress the values using the units from the header
        val point = RadarPoint(
            x = xRaw * xyzUnit,
            y = yRaw * xyzUnit,
            z = zRaw * xyzUnit,
            doppler = dopplerRaw * dopplerUnit,
            snr = snrRaw * snrUnit,
            noise = noiseRaw * noiseUnit
        )
        frameData.points.add(point)
    }
}

/**
 * Parses the Range Profile TLV (Type 302).
 * Translated from parseRangeProfileTLV in parseTLVs.py.
 */
fun parseRangeProfileTLV(payload: ByteBuffer, tlvLength: Int, frameData: ParsedFrameData) {
    val numRangeBins = tlvLength / 4 // Each bin is a 4-byte integer
    val rangeProfile = mutableListOf<Int>()
    for (i in 0 until numRangeBins) {
        if (payload.remaining() < 4) break
        rangeProfile.add(payload.int)
    }
    frameData.rangeProfile = rangeProfile
}

/**
 * Parses the Extended Statistics TLV (Type 306).
 * Translated from parseExtStatsTLV in parseTLVs.py.
 */
fun parseStatsTLV(payload: ByteBuffer, tlvLength: Int, frameData: ParsedFrameData) {
    if (payload.remaining() < 24) return // 2*Int + 8*Short is not quite right, it's 2*Int + 4*Short for temp

    // The python struct is '2I8H' but we only care about some of them
    val interFrameProcTime = payload.int
    val transmitOutTime = payload.int
    // Skip power values (4 shorts = 8 bytes)
    payload.position(payload.position() + 8)
    // Read temperature values
    val tempRx = payload.short
    val tempTx = payload.short
    val tempPM = payload.short
    val tempDIG = payload.short

    frameData.stats = RadarStats(interFrameProcTime, transmitOutTime, tempRx, tempTx, tempPM, tempDIG)
}