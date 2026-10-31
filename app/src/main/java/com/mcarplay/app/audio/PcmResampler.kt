package com.mcarplay.app.audio

/** Streaming s16 LE interpolation with a shared integer clock across input chunks. */
class PcmResampler(
    val sourceRate: Int,
    val sourceChannels: Int,
    val targetRate: Int,
) {
    private var inputFrames = 0L
    private var nextOutput = 0L
    private var previousLeft = 0
    private var previousRight = 0

    init {
        require(sourceRate in 8_000..192_000 && targetRate in 8_000..192_000)
        require(sourceChannels in 1..2)
    }

    fun convert(pcm: ByteArray): ByteArray {
        require(pcm.size % (sourceChannels * 2) == 0) { "Incomplete PCM sample frame" }
        val frames = pcm.size / (sourceChannels * 2)
        if (frames == 0) return EMPTY

        // nextOutput advances by sourceRate per emitted frame and starts at 0,
        // so the emission count is derived arithmetically instead of growing a
        // stream buffer: the exact output size is known before the loop.
        val emittedBefore = nextOutput / sourceRate
        val emittedAfter = ((inputFrames + frames - 1) * targetRate) / sourceRate + 1
        val output = ByteArray((emittedAfter - emittedBefore).toInt() * OUTPUT_FRAME_BYTES)
        var outputOffset = 0
        var offset = 0
        while (offset < pcm.size) {
            val left = sample(pcm, offset)
            val right = if (sourceChannels == 2) sample(pcm, offset + 2) else left
            if (inputFrames == 0L) {
                previousLeft = left
                previousRight = right
            }
            val currentTime = inputFrames * targetRate
            while (nextOutput <= currentTime) {
                val fraction = if (inputFrames == 0L) 0L else nextOutput - (inputFrames - 1) * targetRate
                val leftSample = previousLeft + ((left - previousLeft).toLong() * fraction / targetRate).toInt()
                val rightSample = previousRight + ((right - previousRight).toLong() * fraction / targetRate).toInt()
                outputOffset = writeSample(output, outputOffset, leftSample)
                outputOffset = writeSample(output, outputOffset, rightSample)
                nextOutput += sourceRate
            }
            previousLeft = left
            previousRight = right
            inputFrames++
            offset += sourceChannels * 2
        }
        return output
    }

    private fun sample(
        pcm: ByteArray,
        offset: Int,
    ): Int = (((pcm[offset + 1].toInt() and 0xFF) shl 8) or (pcm[offset].toInt() and 0xFF)).toShort().toInt()

    private fun writeSample(
        output: ByteArray,
        offset: Int,
        value: Int,
    ): Int {
        output[offset] = value.toByte()
        output[offset + 1] = (value shr 8).toByte()
        return offset + 2
    }

    private companion object {
        val EMPTY = ByteArray(0)

        /** 16-bit stereo sample frame written by [convert]. */
        const val OUTPUT_FRAME_BYTES = 4
    }
}
