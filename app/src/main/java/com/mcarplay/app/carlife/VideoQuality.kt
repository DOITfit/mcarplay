package com.mcarplay.app.carlife

/**
 * 由「显示 → 分辨率缩放」和「低配车机模式」推导向 CarWith 请求的编码长边。
 *
 * CarWith 4.0.23 的 CarLife 控制器会读取 HU 的 `VIDEO_ENCODER_INIT` 宽高
 * （`carlife_encode_format` 的 `width/height`），低配车机降低该值可显著减少
 * 解码与带宽压力；CarWith 仍可能不采纳，实际以 `VIDEO_INIT_DONE` 为准。
 */
object VideoQuality {
    const val DEFAULT_MAX_LONG_SIDE = 1920

    /** 低配车机模式的上限：1280 长边约等于 720p（按车机宽高比取偶）。 */
    const val LOW_END_MAX_LONG_SIDE = 1280

    fun maxLongSide(
        scalePercent: Int,
        lowEnd: Boolean,
    ): Int {
        val scaled = (DEFAULT_MAX_LONG_SIDE.toLong() * scalePercent.coerceIn(1, 200) / 100L).toInt()
        val capped = if (lowEnd) minOf(scaled, LOW_END_MAX_LONG_SIDE) else scaled
        return capped.coerceIn(320, DEFAULT_MAX_LONG_SIDE)
    }
}
