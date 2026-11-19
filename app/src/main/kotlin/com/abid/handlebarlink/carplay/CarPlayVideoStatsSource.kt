package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.android.video.CarPlayVideoStats
import kotlinx.coroutines.flow.Flow

/**
 * The CarPlay picture's last window of numbers (`CarPlayScreen.stats`, bound in di/CarPlayModule), for the CarPlay
 * tab's Link quality; a seam because `CarPlayScreen` cannot be built outside its module.
 */
fun interface CarPlayVideoStatsSource {
    fun stats(): Flow<CarPlayVideoStats?>
}
