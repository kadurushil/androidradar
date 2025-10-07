package com.chetak.try1

val UART_MAGIC_WORD = byteArrayOf(0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07)

// --- TLV Type Defines (from tlv_defines.py) ---
// This is the Point Cloud data from the xWRL6432 demo
const val MMWDEMO_OUTPUT_EXT_MSG_DETECTED_POINTS = 301
// This is the Range Profile data
const val MMWDEMO_OUTPUT_EXT_MSG_RANGE_PROFILE_MAJOR = 302
// This contains statistics like processing time and temperature
const val MMWDEMO_OUTPUT_MSG_EXT_STATS = 306

// --- Struct Sizes ---
// From parsePointCloudExtTLV in parseTLVs.py: '4h2B' = 4*2 + 2*1 = 10 bytes per point
const val SIZEOF_DETECTED_POINT_EXT_STRUCT = 10
// From parsePointCloudExtTLV: '4f2h' = 4*4 + 2*2 = 20 bytes for the unit header
const val SIZEOF_POINT_UNIT_STRUCT = 20