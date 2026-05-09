package com.comfymobile.data.png

/**
 * Standard CRC-32 used by the PNG specification (ISO 3309 /
 * ITU-T V.42), polynomial 0xEDB88320. Pure-Kotlin so it works on
 * commonMain without expect/actual.
 *
 * The implementation uses the canonical 256-entry lookup table; for
 * the workflow embed/extract use case (chunk lengths ≤ a few KB) the
 * table-driven version is more than fast enough.
 */
internal object PngCrc32 {

    private val TABLE: IntArray = IntArray(256).also { table ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) {
                    -0x12477ce0 xor (c ushr 1) // 0xEDB88320
                } else {
                    c ushr 1
                }
            }
            table[n] = c
        }
    }

    /** Return the CRC-32 of [bytes] in `[offset, offset + length)`. */
    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        var crc = -1 // 0xFFFFFFFF
        var i = offset
        val end = offset + length
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            crc = TABLE[(crc xor b) and 0xFF] xor (crc ushr 8)
            i++
        }
        return crc.inv()
    }

    /** CRC over the concatenation of two byte ranges (used to CRC the
     *  chunk type + chunk data without copying them into one buffer). */
    fun compute(typeBytes: ByteArray, dataBytes: ByteArray): Int {
        var crc = -1
        for (b in typeBytes) {
            val v = b.toInt() and 0xFF
            crc = TABLE[(crc xor v) and 0xFF] xor (crc ushr 8)
        }
        for (b in dataBytes) {
            val v = b.toInt() and 0xFF
            crc = TABLE[(crc xor v) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv()
    }
}
