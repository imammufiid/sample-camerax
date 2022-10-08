package com.mufiid.xcamerax.utils

import androidx.camera.core.AspectRatio
import androidx.camera.video.Quality
import androidx.camera.video.VideoRecordEvent
import java.util.*
import java.util.concurrent.TimeUnit


/**
 * a helper function to retrieve the aspect ratio string from a Quality enum.
 */
fun Quality.getAspectRatioString(quality: Quality, portraitMode: Boolean): String {
    val hdQualities = arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
    val ratio =
        when {
            hdQualities.contains(quality) -> Pair(16, 9)
            quality == Quality.SD -> Pair(4, 3)
            else -> throw UnsupportedOperationException()
        }

    return if (portraitMode) "V,${ratio.second}:${ratio.first}"
    else "H,${ratio.first}:${ratio.second}"
}

/**
 * a helper function to retrieve the aspect ratio from a QualitySelector enum.
 */
fun Quality.getAspectRatio(quality: Quality): Int {
    return when {
        arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
            .contains(quality) -> AspectRatio.RATIO_16_9
        (quality == Quality.SD) -> AspectRatio.RATIO_4_3
        else -> throw UnsupportedOperationException()
    }
}

/**
 * A helper extended function to get the name(string) for the VideoRecordEvent.
 */
fun VideoRecordEvent.getNameString(): String {
    return when (this) {
        is VideoRecordEvent.Status -> "Status"
        is VideoRecordEvent.Start -> "Started"
        is VideoRecordEvent.Finalize -> "Finalized"
        is VideoRecordEvent.Pause -> "Paused"
        is VideoRecordEvent.Resume -> "Resumed"
        else -> throw IllegalArgumentException("Unknown VideoRecordEvent: $this")
    }
}

fun getFormattedStopWatchTime(ms: Long, includeMillis: Boolean = false): String {
    var milliseconds = ms
    val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
    milliseconds -= TimeUnit.HOURS.toMillis(hours)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds)
    milliseconds -= TimeUnit.MINUTES.toMillis(minutes)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds)
    if (!includeMillis) {
        return "${if (hours < 10) "0" else ""}$hours:" +
                "${if (minutes < 10) "0" else ""}$minutes:" +
                "${if (seconds < 10) "0" else ""}$seconds"
    }
    milliseconds -= TimeUnit.SECONDS.toMillis(seconds)
    milliseconds /= 10
    return "${if (hours < 10) "0" else ""}$hours:" +
            "${if (minutes < 10) "0" else ""}$minutes:" +
            "${if (seconds < 10) "0" else ""}$seconds:" +
            "${if (milliseconds < 10) "0" else ""}$milliseconds"
}

fun getFormattedStopWatchTime(seconds: Int): String {
    val hours: Int = seconds / 3600
    val minutes: Int = seconds % 3600 / 60
    val secs: Int = seconds % 60

    return String
        .format(
            Locale.getDefault(),
            "%02d:%02d:%02d", hours,
            minutes, secs
        )
}