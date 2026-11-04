package com.personal.minivideooverlay

object OverlayConfig {
    const val OUTPUT_WIDTH = 1920
    const val OUTPUT_HEIGHT = 1080
    const val VIDEO_BITRATE = 8_000_000
    const val VIDEO_BITRATE_60FPS = 12_000_000
    const val OUTPUT_FOLDER = "Movies/MiniVideoOverlay"
}

enum class ExportState { Idle, Validating, Preparing, Exporting, Finalizing, Completed, Cancelled, Failed }
