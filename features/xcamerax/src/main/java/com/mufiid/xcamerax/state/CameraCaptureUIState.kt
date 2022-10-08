package com.mufiid.xcamerax.state

enum class CameraCaptureUIState {
    IDLE,       // Not recording, all UI controls are active.
    RECORDING,  // Camera is recording, only display Pause/Resume & Stop button.
    FINALIZED,  // Recording just completes, disable all RECORDING UI controls.
    RECOVERY    // For future use.
}