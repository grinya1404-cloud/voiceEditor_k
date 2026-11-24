package com.grinya.voicesampleprep.model

class ExportSettings {
    @JvmField var byMarkers = false
    @JvmField var onlySelection = false
    @JvmField var bits = 0
    @JvmField var rate = 0
    @JvmField var stereoMono = false
    @JvmField var trueMono = false
    @JvmField var normalize = false
    @JvmField var fade = false
    @JvmField var trimSilence = false
    @JvmField var denoise = false
    @JvmField var nameTemplate: String? = null
    
    // --- Новые поля ---
    @JvmField var fadeMs: Int = 5
    @JvmField var normalizeTarget: Float = 0.9f
}