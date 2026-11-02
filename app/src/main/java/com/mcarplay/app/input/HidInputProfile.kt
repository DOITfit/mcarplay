package com.mcarplay.app.input

/**
 * 一台 HID 设备的描述符解析结果（按 uuid 缓存）。
 *
 * 触摸 move 报文可达 60–120Hz，若每一帧都重新扫描描述符，事件通道线程会把时间花在
 * 重复解析上，直接体现为拖拽跟手变差。描述符在会话内不变，所以在事件通道建立时
 * 预解析一次，热路径只做报文解析与发送。
 */
class HidInputProfile(
    val keyMap: ConsumerKeys.KeyMap?,
    val touchFormat: TouchReports.Format?,
    val navigation: Boolean,
) {
    companion object {
        fun from(descriptor: ByteArray?): HidInputProfile =
            if (descriptor == null || descriptor.isEmpty()) {
                HidInputProfile(null, null, false)
            } else {
                HidInputProfile(
                    keyMap = ConsumerKeys.parseDescriptor(descriptor),
                    touchFormat = TouchReports.formatFromDescriptor(descriptor),
                    navigation = HidNavigation.isNavigationDevice(descriptor),
                )
            }
    }
}
