package com.mufiid.xcamerax.model

import androidx.camera.core.CameraSelector
import androidx.camera.video.Quality

data class CameraCapability(val camSelector: CameraSelector, val qualities: List<Quality>)
