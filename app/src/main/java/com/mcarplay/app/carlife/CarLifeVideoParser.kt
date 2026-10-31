package com.mcarplay.app.carlife

import com.mcarplay.app.video.ExternalVideoFrame

/** A complete Annex B access unit delivered by the CarLife video socket. */
data class CarLifeVideoAccessUnit(
    val timestamp: Long,
    val data: ByteArray,
    val keyFrame: Boolean,
    val sps: ByteArray?,
    val pps: ByteArray?,
    /**
     * Dimensions parsed when the parser last saw a parameter set. CarWith keeps
     * the same SPS for a whole stream, so caching it here avoids re-parsing the
     * SPS for every frame (the parser runs at video frame rate). Null falls
     * back to parsing [sps] on demand.
     */
    val dimensionsHint: Pair<Int, Int>? = null,
) {
    val isCodecConfig: Boolean
        get() = sps != null && pps != null

    fun toExternalVideoFrame(): ExternalVideoFrame =
        ExternalVideoFrame(
            data = data,
            keyFrame = keyFrame,
            timestampUs = timestamp * 1_000_000L,
        )

    fun dimensions(): Pair<Int, Int>? = dimensionsHint ?: sps?.let(::spsDimensions)
}

/**
 * Converts CarLife's per-packet Annex B data into complete access units.
 *
 * CarLife timestamps are Unix seconds and can be shared by many frames, so they
 * are not access-unit boundaries. H.264 semantics are used instead: a new AUD
 * or parameter set/SEI starts an access unit, and another VCL slice after a
 * complete first slice also starts one.
 */
class CarLifeVideoParser {
    private var pendingTimestamp: Long? = null

    /**
     * Access-unit NALs are concatenated into one growable buffer instead of
     * one copy per NAL, so a frame costs a single allocation at flush time.
     */
    private var pendingData = ByteArray(0)
    private var pendingBytes = 0
    private var pendingHasVcl = false
    private var pendingHasIdr = false
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null

    /** Unconsumed Annex B bytes: the trailing NAL, which may still be incomplete. */
    private var annexBBuffer = ByteArray(INITIAL_BUFFER_BYTES)
    private var annexBSize = 0
    private var annexBTimestamp: Long? = null

    /** Start-code prefix length at offset 0, or 0 while no start code is known. */
    private var headPrefix = 0

    /** First offset whose start code still needs to be scanned after an append. */
    private var nextScan = 0

    private var latestSps: ByteArray? = null
    private var latestPps: ByteArray? = null
    private var latestDimensions: Pair<Int, Int>? = null

    /** Reused Exp-Golomb reader: the VCL lookahead runs once per video packet. */
    private val firstMb = ExpGolombReader()

    fun push(
        timestamp: Long,
        payload: ByteArray,
    ): List<CarLifeVideoAccessUnit> {
        if (payload.isEmpty()) return emptyList()
        val complete = ArrayList<CarLifeVideoAccessUnit>(2)

        // Complete the previous trailing NAL only when the timestamp changes.
        // Within one second, H.264 start codes are the reliable frame boundary.
        val bufferedAt = annexBTimestamp
        if (bufferedAt != null && bufferedAt != timestamp && annexBSize > 0) {
            processAvailableNals(bufferedAt, final = true, target = complete)
        }
        if (annexBSize == 0) {
            annexBTimestamp = timestamp
            headPrefix = 0
            nextScan = 0
        }
        append(payload)
        processAvailableNals(timestamp, final = false, target = complete)
        return complete
    }

    fun flush(): List<CarLifeVideoAccessUnit> {
        val complete = ArrayList<CarLifeVideoAccessUnit>(1)
        annexBTimestamp?.let { timestamp ->
            processAvailableNals(timestamp, final = true, target = complete)
        }
        flushAccessUnit(complete)
        return complete
    }

    /**
     * Consumes every complete NAL in [annexBBuffer] and keeps the trailing one
     * (whose body may still be in flight) for the next packet. Start-code
     * scanning resumes at [nextScan] instead of rescanning the retained NAL,
     * which keeps fragmented key frames linear instead of quadratic.
     */
    private fun processAvailableNals(
        timestamp: Long,
        final: Boolean,
        target: MutableList<CarLifeVideoAccessUnit>,
    ) {
        if (annexBSize < 3) {
            if (final) clearAnnexBBuffer()
            return
        }

        var currentStart = if (headPrefix > 0) 0 else -1
        var currentPrefix = headPrefix
        var index = if (currentStart >= 0) maxOf(headPrefix, nextScan) else maxOf(0, nextScan)
        while (index + 3 <= annexBSize) {
            val prefix = startCodeLengthAt(index)
            if (prefix == 0) {
                index++
                continue
            }
            if (currentStart >= 0) {
                consumeNal(currentStart + currentPrefix, index, timestamp, target)
            }
            currentStart = index
            currentPrefix = prefix
            index += prefix
        }

        if (final) {
            if (currentStart >= 0 && currentStart + currentPrefix < annexBSize) {
                consumeNal(currentStart + currentPrefix, annexBSize, timestamp, target)
            }
            clearAnnexBBuffer()
            return
        }

        if (currentStart < 0) {
            // No start code yet: keep the bytes and re-check the boundary next time.
            nextScan = maxOf(0, annexBSize - 3)
            return
        }

        // Flush in consumeNal would drop a trailing NAL whose start code has
        // already arrived but whose body is still waiting for the next packet.
        if (trailingStartsAccessUnit(currentStart + currentPrefix)) {
            flushAccessUnit(target)
        }

        val tailSize = annexBSize - currentStart
        System.arraycopy(annexBBuffer, currentStart, annexBBuffer, 0, tailSize)
        annexBSize = tailSize
        headPrefix = currentPrefix
        nextScan = maxOf(headPrefix, tailSize - 3)
    }

    /** Append [payload] to the retained Annex B buffer, growing it as needed. */
    private fun append(payload: ByteArray) {
        val required = annexBSize + payload.size
        if (required > annexBBuffer.size) {
            var capacity = maxOf(annexBBuffer.size, INITIAL_BUFFER_BYTES)
            while (capacity < required) capacity = maxOf(required, capacity * 2)
            annexBBuffer = annexBBuffer.copyOf(capacity)
        }
        payload.copyInto(annexBBuffer, annexBSize)
        annexBSize = required
    }

    /** Returns 3/4 for a start code at [index], or 0 when there is none. */
    private fun startCodeLengthAt(index: Int): Int {
        if (index + 3 > annexBSize || annexBBuffer[index] != 0.toByte() || annexBBuffer[index + 1] != 0.toByte()) return 0
        if (annexBBuffer[index + 2] == 1.toByte()) return 3
        if (index + 4 <= annexBSize && annexBBuffer[index + 2] == 0.toByte() && annexBBuffer[index + 3] == 1.toByte()) {
            return 4
        }
        return 0
    }

    /** True when the NAL at [bodyStart] opens a new access unit. */
    private fun trailingStartsAccessUnit(bodyStart: Int): Boolean {
        if (!pendingHasVcl || bodyStart >= annexBSize) return false
        val type = annexBBuffer[bodyStart].toInt() and 0x1F
        return type == 9 ||
            type == 6 ||
            type == 7 ||
            type == 8 ||
            ((type == 1 || type == 5) && firstMbInSliceAt(bodyStart, annexBSize) == 0)
    }

    private fun clearAnnexBBuffer() {
        annexBSize = 0
        annexBTimestamp = null
        headPrefix = 0
        nextScan = 0
    }

    private fun consumeNal(
        start: Int,
        end: Int,
        timestamp: Long,
        target: MutableList<CarLifeVideoAccessUnit>,
    ) {
        if (end <= start) return
        val type = annexBBuffer[start].toInt() and 0x1F
        val isVcl = type == 1 || type == 5
        val startsAccessUnit =
            type == 9 ||
                (pendingHasVcl && (type == 6 || type == 7 || type == 8)) ||
                (pendingHasVcl && isVcl && firstMbInSliceAt(start, end) == 0)
        if (startsAccessUnit) flushAccessUnit(target)
        if (pendingTimestamp == null) pendingTimestamp = timestamp
        appendPendingNal(start, end)
        pendingHasVcl = pendingHasVcl || isVcl
        when (type) {
            5 -> pendingHasIdr = true
            7 -> {
                val sps = annexBBuffer.copyOfRange(start, end)
                pendingSps = sps
                latestSps = sps
                latestDimensions = spsDimensions(sps)
            }

            8 -> {
                val pps = annexBBuffer.copyOfRange(start, end)
                pendingPps = pps
                latestPps = pps
            }
        }
        if (pendingBytes > MAX_ACCESS_UNIT_BYTES) resetAccessUnit()
    }

    /** Appends `00 00 00 01` + the NAL bytes to the pending access unit. */
    private fun appendPendingNal(
        start: Int,
        end: Int,
    ) {
        val needed = pendingBytes + ANNEX_B_START_CODE.size + (end - start)
        if (needed > pendingData.size) {
            var capacity = maxOf(pendingData.size, INITIAL_PENDING_BYTES)
            while (capacity < needed) capacity = maxOf(needed, capacity * 2)
            pendingData = pendingData.copyOf(capacity)
        }
        ANNEX_B_START_CODE.copyInto(pendingData, pendingBytes)
        annexBBuffer.copyInto(pendingData, pendingBytes + ANNEX_B_START_CODE.size, start, end)
        pendingBytes = needed
    }

    private fun flushAccessUnit(target: MutableList<CarLifeVideoAccessUnit>) {
        val timestamp = pendingTimestamp
        if (timestamp == null || pendingBytes == 0) {
            resetAccessUnit()
            return
        }
        target +=
            CarLifeVideoAccessUnit(
                timestamp = timestamp,
                data = pendingData.copyOf(pendingBytes),
                keyFrame = pendingHasIdr,
                // Repeated parameter sets are not required in every frame, but
                // keeping the latest pair lets VideoSender build avcC reliably.
                sps = pendingSps ?: latestSps,
                pps = pendingPps ?: latestPps,
                dimensionsHint = latestDimensions,
            )
        resetAccessUnit()
    }

    private fun resetAccessUnit() {
        pendingTimestamp = null
        pendingBytes = 0
        pendingHasVcl = false
        pendingHasIdr = false
        pendingSps = null
        pendingPps = null
    }

    private fun firstMbInSliceAt(
        start: Int,
        end: Int,
    ): Int? {
        if (end - start < 2) return null
        val type = annexBBuffer[start].toInt() and 0x1F
        if (type != 1 && type != 5) return null
        firstMb.reset(annexBBuffer, start + 1, minOf(end, start + FIRST_MB_PROBE_BYTES))
        return firstMb.readUe()
    }

    private companion object {
        val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)
        const val INITIAL_BUFFER_BYTES = 64 * 1024
        const val INITIAL_PENDING_BYTES = 64 * 1024

        /** Bytes after the NAL header inspected for first_mb_in_slice (header + ue(v)). */
        const val FIRST_MB_PROBE_BYTES = 32
        const val MAX_ACCESS_UNIT_BYTES = 4 * 1024 * 1024
    }
}

internal fun splitAnnexB(data: ByteArray): List<ByteArray> {
    val starts = annexBStartCodes(data)
    if (starts.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)
    return starts.mapIndexed { unit, (start, prefix) ->
        val bodyStart = start + prefix
        val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1].first else data.size
        data.copyOfRange(bodyStart, bodyEnd)
    }
}

internal fun annexBStartCodes(data: ByteArray): List<Pair<Int, Int>> {
    val starts = ArrayList<Pair<Int, Int>>()
    var index = 0
    while (index + 3 <= data.size) {
        when {
            index + 4 <= data.size &&
                data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() &&
                data[index + 3] == 1.toByte() -> {
                starts += index to 4
                index += 4
            }

            data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 1.toByte() -> {
                starts += index to 3
                index += 3
            }

            else -> index++
        }
    }
    return starts
}

/**
 * Reads the unsigned Exp-Golomb value immediately after the one-byte NAL
 * header. Emulation-prevention bytes are removed on the fly from the small
 * prefix (no intermediate copy).
 */
internal fun firstMbInSlice(nal: ByteArray): Int? {
    if (nal.size < 2) return null
    val type = nal[0].toInt() and 0x1F
    if (type != 1 && type != 5) return null
    val reader = ExpGolombReader()
    reader.reset(nal, 1, minOf(nal.size, 32))
    return reader.readUe()
}

/**
 * Bit reader over RBSP bytes with emulation-prevention removal. Reusable so
 * the per-packet access-unit lookahead does not allocate.
 */
private class ExpGolombReader {
    private var data = EMPTY
    private var index = 0
    private var end = 0
    private var zeros = 0
    private var current = 0
    private var remaining = 0

    fun reset(
        data: ByteArray,
        start: Int,
        end: Int,
    ) {
        this.data = data
        index = start
        this.end = end
        zeros = 0
        current = 0
        remaining = 0
    }

    /** Returns the ue(v) value at the current position, or null when truncated. */
    fun readUe(): Int? {
        var leadingZeros = 0
        while (true) {
            when (readBit()) {
                null -> return null
                1 -> break
                else -> {
                    leadingZeros++
                    if (leadingZeros >= 32) return null
                }
            }
        }
        var value = 1
        repeat(leadingZeros) {
            val next = readBit() ?: return null
            value = (value shl 1) or next
        }
        return value - 1
    }

    private fun readBit(): Int? {
        if (remaining == 0) {
            val byte = nextRbspByte() ?: return null
            current = byte
            remaining = 8
        }
        remaining--
        return (current ushr remaining) and 1
    }

    private fun nextRbspByte(): Int? {
        while (index < end) {
            val value = data[index++].toInt() and 0xFF
            if (zeros >= 2 && value == 3) {
                zeros = 0
                continue
            }
            zeros = if (value == 0) zeros + 1 else 0
            return value
        }
        return null
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/** Parse pic_width_in_mbs/pic_height_in_map_units from a baseline/high SPS. */
internal fun spsDimensions(sps: ByteArray): Pair<Int, Int>? {
    if (sps.size < 4) return null
    val rbsp = ArrayList<Byte>(sps.size)
    var zeros = 0
    for (index in 1 until sps.size) {
        val value = sps[index].toInt() and 0xFF
        if (zeros >= 2 && value == 3) {
            zeros = 0
            continue
        }
        rbsp += value.toByte()
        zeros = if (value == 0) zeros + 1 else 0
    }
    return runCatching {
        val bits = BitReader(rbsp.toByteArray())
        bits.skip(24) // profile_idc, constraint flags, level_idc
        bits.readUe() // seq_parameter_set_id
        var chromaFormatIdc = 1
        var separateColourPlane = 0
        val profile = sps[1].toInt() and 0xFF
        if (profile in HIGH_PROFILES) {
            chromaFormatIdc = bits.readUe()
            if (chromaFormatIdc == 3) separateColourPlane = bits.readBit()
            bits.readUe() // bit_depth_luma_minus8
            bits.readUe() // bit_depth_chroma_minus8
            bits.skip(1) // qpprime_y_zero_transform_bypass_flag
            if (bits.readBit() == 1) {
                val scalingLists = if (chromaFormatIdc != 3) 8 else 12
                repeat(scalingLists) {
                    if (bits.readBit() == 1) {
                        val size = if (it < 6) 16 else 64
                        var last = 8
                        var next = 8
                        for (unused in 0 until size) {
                            if (next != 0) next = (last + bits.readSe() + 256) % 256
                            last = if (next == 0) last else next
                        }
                    }
                }
            }
        }
        bits.readUe() // log2_max_frame_num_minus4
        val picOrderCntType = bits.readUe()
        if (picOrderCntType == 0) {
            bits.readUe()
        } else if (picOrderCntType == 1) {
            bits.skip(1)
            bits.readSe()
            bits.readSe()
            repeat(bits.readUe()) { bits.readSe() }
        }
        bits.readUe() // max_num_ref_frames
        bits.skip(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = bits.readUe() + 1
        val heightMapUnits = bits.readUe() + 1
        val frameMbsOnly = bits.readBit()
        if (frameMbsOnly == 0) bits.skip(1)
        bits.skip(1) // direct_8x8_inference_flag
        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (bits.readBit() == 1) {
            cropLeft = bits.readUe()
            cropRight = bits.readUe()
            cropTop = bits.readUe()
            cropBottom = bits.readUe()
        }
        val chromaArrayType = if (separateColourPlane == 1) 0 else chromaFormatIdc
        val subWidthC = if (chromaArrayType == 1 || chromaArrayType == 2) 2 else 1
        val subHeightC = if (chromaArrayType == 1) 2 else 1
        val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
        val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnly) else subHeightC * (2 - frameMbsOnly)
        val width = widthMbs * 16 - (cropLeft + cropRight) * cropUnitX
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 - (cropTop + cropBottom) * cropUnitY
        width to height
    }.getOrNull()
}

private class BitReader(
    private val data: ByteArray,
) {
    private var bit = 0

    fun readBit(): Int {
        require(bit < data.size * 8) { "SPS bitstream ended" }
        val value = (data[bit / 8].toInt() ushr (7 - bit % 8)) and 1
        bit++
        return value
    }

    fun skip(count: Int) {
        require(bit + count <= data.size * 8) { "SPS bitstream ended" }
        bit += count
    }

    fun readUe(): Int {
        var zeros = 0
        while (readBit() == 0) {
            zeros++
            require(zeros < 32) { "Invalid Exp-Golomb code" }
        }
        var value = 1
        repeat(zeros) { value = (value shl 1) or readBit() }
        return value - 1
    }

    fun readSe(): Int {
        val code = readUe()
        return if (code and 1 == 0) -(code / 2) else (code + 1) / 2
    }
}

private val HIGH_PROFILES =
    setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
