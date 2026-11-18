package com.hq.retrocam.gl

enum class StampKind { CAMCORDER, FILM }

data class LookStyle(
    val id: Int,
    val name: String,
    val grain: Float,
    val vignette: Float,
    val leak: Float,
    val stamp: StampKind,
)

object Styles {
    val all = listOf(
        LookStyle(0, "原片", 0f, 0f, 0f, StampKind.FILM),
        LookStyle(1, "DV 2003", 0.35f, 0.25f, 0f, StampKind.CAMCORDER),
        LookStyle(2, "CCD 卡片机", 0.3f, 0.2f, 0f, StampKind.FILM),
        LookStyle(3, "暖调人像", 0.45f, 0.3f, 0f, StampKind.FILM),
        LookStyle(4, "经典负片", 0.5f, 0.35f, 0f, StampKind.FILM),
        LookStyle(5, "怀旧负片", 0.5f, 0.35f, 0.5f, StampKind.FILM),
        LookStyle(6, "正片", 0.35f, 0.3f, 0f, StampKind.FILM),
        LookStyle(7, "黑白 400", 0.8f, 0.4f, 0f, StampKind.FILM),
        LookStyle(8, "VHS 录像带", 0.4f, 0.3f, 0f, StampKind.CAMCORDER),
        LookStyle(9, "Super 8", 0.9f, 0.6f, 0.6f, StampKind.FILM),
    )
}

/** Live parameters shared between UI and render thread. */
class LookParams {
    @Volatile var style: Int = 1
    @Volatile var intensity: Float = 1f
    @Volatile var grain: Float = 0.35f
    @Volatile var vignette: Float = 0.25f
    @Volatile var leak: Float = 0f
    @Volatile var dateStamp: Boolean = true
    @Volatile var stampKind: StampKind = StampKind.CAMCORDER
}
