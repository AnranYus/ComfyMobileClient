package com.comfymobile.data.png

/**
 * Read/write `tEXt` chunks on PNG byte arrays.
 *
 * ComfyUI embeds workflow / prompt JSON in `tEXt` chunks: keyword
 * "workflow" carries the UI-format workflow JSON, keyword "prompt"
 * carries the API-format prompt JSON. The mobile client does the
 * exact same thing on submit so generated PNGs round-trip cleanly
 * back into the desktop ComfyUI editor (per ADR-0003).
 *
 * PNG file format reference:
 *   8-byte signature: 89 50 4E 47 0D 0A 1A 0A
 *   then a sequence of chunks, each:
 *     4 bytes  length (big-endian, data length, NOT counting type/CRC)
 *     4 bytes  type (ASCII)
 *     N bytes  data
 *     4 bytes  CRC-32 over (type + data)
 *
 * `tEXt` chunk data layout: `<keyword><null><text>`
 *   - keyword: 1..79 Latin-1 bytes, NO null
 *   - null separator: 0x00
 *   - text: zero or more Latin-1 bytes (no null in text — ComfyUI
 *     stuffs JSON in here, which is plain ASCII apart from the
 *     escape sequences kotlinx-serialization writes)
 */
object PngTextChunkCodec {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val TEXT_TYPE = "tEXt".encodeToByteArray()
    private val IEND_TYPE = "IEND".encodeToByteArray()

    /**
     * Insert a `tEXt` chunk with the given keyword/text immediately
     * before the IEND chunk. If a chunk with the same keyword already
     * exists, it is replaced.
     *
     * @throws PngFormatException if [png] is not a syntactically valid
     *         PNG (bad signature or no IEND).
     * @throws IllegalArgumentException if [keyword] is empty, longer
     *         than 79 bytes, or contains a null byte.
     */
    fun embed(png: ByteArray, keyword: String, text: String): ByteArray {
        require(keyword.isNotEmpty()) { "tEXt keyword must not be empty" }
        val keywordBytes = keyword.encodeToByteArray()
        require(keywordBytes.size in 1..79) { "tEXt keyword must be 1..79 Latin-1 bytes" }
        require(0.toByte() !in keywordBytes) { "tEXt keyword must not contain null byte" }

        verifySignature(png)

        val chunks = scanChunks(png)
        val iendIndex = chunks.indexOfFirst { it.type.contentEquals(IEND_TYPE) }
        if (iendIndex < 0) throw PngFormatException("PNG has no IEND chunk")

        // Build the new tEXt chunk bytes.
        val newChunkData = ByteArray(keywordBytes.size + 1 + text.encodeToByteArray().size).also {
            keywordBytes.copyInto(it, destinationOffset = 0)
            it[keywordBytes.size] = 0
            text.encodeToByteArray().copyInto(it, destinationOffset = keywordBytes.size + 1)
        }
        val newChunk = encodeChunk(type = TEXT_TYPE, data = newChunkData)

        // Drop any existing tEXt chunks with the same keyword so we
        // don't duplicate them on repeated embed.
        val survivingChunks = chunks.filterNot { existing ->
            existing.type.contentEquals(TEXT_TYPE) && parseTextChunkKeyword(existing.data) == keyword
        }

        // Re-find IEND in the surviving list (it must still be there).
        val iendInSurviving = survivingChunks.indexOfFirst { it.type.contentEquals(IEND_TYPE) }
        check(iendInSurviving >= 0) { "IEND missing after filtering — should be impossible" }

        val out = ArrayList<ByteArray>(survivingChunks.size + 2)
        out.add(PNG_SIGNATURE)
        for ((index, chunk) in survivingChunks.withIndex()) {
            if (index == iendInSurviving) {
                out.add(newChunk)
            }
            out.add(chunk.bytes)
        }
        return concat(out)
    }

    /**
     * Find the first `tEXt` chunk whose keyword equals [keyword] and
     * return its text. Returns null when no chunk matches.
     *
     * @throws PngFormatException on a syntactically invalid PNG —
     *         including the case where the byte stream is truncated
     *         before IEND. PNG spec requires every valid PNG to end
     *         in IEND; rejecting "no IEND" here keeps malformed
     *         payloads from silently being treated as "valid PNG, no
     *         metadata". (Per @Lily PR #8 review msg `ee8e36e3`.)
     */
    fun extract(png: ByteArray, keyword: String): String? {
        val chunks = scanAndRequireIend(png)
        for (chunk in chunks) {
            if (!chunk.type.contentEquals(TEXT_TYPE)) continue
            val pair = parseTextChunk(chunk.data) ?: continue
            if (pair.first == keyword) return pair.second
        }
        return null
    }

    /**
     * Return all `tEXt` chunks as keyword → text. If the same keyword
     * appears multiple times the first one wins (PNG spec allows
     * multiple but the ComfyUI convention has one per keyword).
     *
     * @throws PngFormatException on a syntactically invalid PNG,
     *         including missing-IEND.
     */
    fun list(png: ByteArray): Map<String, String> {
        val chunks = scanAndRequireIend(png)
        val out = LinkedHashMap<String, String>()
        for (chunk in chunks) {
            if (!chunk.type.contentEquals(TEXT_TYPE)) continue
            val (kw, txt) = parseTextChunk(chunk.data) ?: continue
            // commonMain has no Map.putIfAbsent — emulate.
            if (kw !in out) out[kw] = txt
        }
        return out
    }

    /**
     * Verify signature, parse all chunks, and require IEND to be
     * present. Used by every read path so callers cannot consume a
     * "valid PNG-without-IEND" — that is, a stream that's been
     * truncated just before the terminating chunk.
     */
    private fun scanAndRequireIend(png: ByteArray): List<Chunk> {
        verifySignature(png)
        val chunks = scanChunks(png)
        if (chunks.none { it.type.contentEquals(IEND_TYPE) }) {
            throw PngFormatException("invalid PNG: missing IEND chunk")
        }
        return chunks
    }

    // ----------------------------------------------------------------- internals

    /**
     * One PNG chunk parsed back into structural pieces. [bytes] is the
     * full on-wire chunk (length + type + data + CRC) so the caller
     * can re-emit it without re-encoding.
     */
    private data class Chunk(
        val type: ByteArray,
        val data: ByteArray,
        val bytes: ByteArray,
    )

    private fun verifySignature(png: ByteArray) {
        if (png.size < PNG_SIGNATURE.size) {
            throw PngFormatException("not a PNG: too short")
        }
        for (i in PNG_SIGNATURE.indices) {
            if (png[i] != PNG_SIGNATURE[i]) {
                throw PngFormatException("not a PNG: signature mismatch")
            }
        }
    }

    /**
     * Walk chunks starting at offset 8. Each chunk's CRC is verified;
     * a CRC mismatch throws [PngFormatException] so callers can
     * distinguish "valid PNG, no workflow" from "corrupt PNG".
     */
    private fun scanChunks(png: ByteArray): List<Chunk> {
        val out = ArrayList<Chunk>()
        var i = PNG_SIGNATURE.size
        while (i < png.size) {
            if (i + 8 > png.size) {
                throw PngFormatException("truncated PNG: chunk header beyond end")
            }
            val length = readUInt32(png, i)
            if (length < 0) {
                throw PngFormatException("invalid PNG chunk length at offset $i")
            }
            val type = png.copyOfRange(i + 4, i + 8)
            val dataStart = i + 8
            val crcOffset = dataStart + length
            if (crcOffset + 4 > png.size) {
                throw PngFormatException("truncated PNG: chunk extends beyond end at offset $i")
            }
            val data = png.copyOfRange(dataStart, dataStart + length)
            val expectedCrc = readUInt32(png, crcOffset)
            val actualCrc = PngCrc32.compute(typeBytes = type, dataBytes = data)
            if (expectedCrc != actualCrc) {
                throw PngFormatException(
                    "CRC mismatch in chunk '${type.decodeToString()}' " +
                        "at offset $i (expected ${expectedCrc.toUInt().toString(16)}, " +
                        "got ${actualCrc.toUInt().toString(16)})"
                )
            }
            val chunkBytes = png.copyOfRange(i, crcOffset + 4)
            out.add(Chunk(type = type, data = data, bytes = chunkBytes))
            i = crcOffset + 4
        }
        return out
    }

    /**
     * Build the on-wire bytes for a single chunk: length(4) + type(4)
     *   + data(N) + CRC(4).
     */
    private fun encodeChunk(type: ByteArray, data: ByteArray): ByteArray {
        require(type.size == 4) { "PNG chunk type must be 4 ASCII bytes" }
        val out = ByteArray(4 + 4 + data.size + 4)
        writeUInt32(out, 0, data.size)
        type.copyInto(out, destinationOffset = 4)
        data.copyInto(out, destinationOffset = 8)
        val crc = PngCrc32.compute(typeBytes = type, dataBytes = data)
        writeUInt32(out, 8 + data.size, crc)
        return out
    }

    private fun parseTextChunk(data: ByteArray): Pair<String, String>? {
        val nullIdx = data.indexOf(0)
        if (nullIdx <= 0 || nullIdx > 79) return null
        val keyword = data.copyOfRange(0, nullIdx).decodeToString()
        val text = data.copyOfRange(nullIdx + 1, data.size).decodeToString()
        return keyword to text
    }

    private fun parseTextChunkKeyword(data: ByteArray): String? {
        val nullIdx = data.indexOf(0)
        if (nullIdx <= 0 || nullIdx > 79) return null
        return data.copyOfRange(0, nullIdx).decodeToString()
    }

    private fun ByteArray.indexOf(b: Byte): Int {
        for (i in indices) if (this[i] == b) return i
        return -1
    }

    private fun readUInt32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun writeUInt32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (p in parts) {
            p.copyInto(out, destinationOffset = offset)
            offset += p.size
        }
        return out
    }
}

/** Thrown when a byte array is not a syntactically valid PNG. */
class PngFormatException(message: String) : RuntimeException(message)
