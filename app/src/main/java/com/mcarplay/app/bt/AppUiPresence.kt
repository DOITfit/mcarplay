package com.mcarplay.app.bt

/** 最近一次主界面真正到前台的时刻；用于核对「后台拉起界面」是否被系统静默拦下。 */
object AppUiPresence {
    @Volatile
    var lastBringUpAt: Long = 0L
}
