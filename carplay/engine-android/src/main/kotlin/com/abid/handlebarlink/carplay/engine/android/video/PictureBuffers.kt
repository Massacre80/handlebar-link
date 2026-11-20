package com.abid.handlebarlink.carplay.engine.android.video

/**
 * The screen's picture buffers (docs/PLAN.md performance rules: decrypt into reused buffers): the screen stream reader
 * decrypts each picture into one [take] hands out, and the decoder gives it back through its
 * [ScreenDecoder.FrameRecycler] ([give]) once it has copied the picture into the codec's input. On the Poco a picture
 * is 12-23 KB at 60 fps and an IDR hundreds of KB, so without reuse each one is a fresh large-object allocation.
 *
 * Thread-safe: taken on the engine's thread, given back on the decoder's. At most [maxKept] buffers are kept; a new
 * one is sized with some headroom so the next, slightly larger picture still fits.
 */
internal class PictureBuffers(
    private val maxKept: Int = MAX_KEPT,
) {
    private val free = ArrayList<ByteArray>()

    /** Counters for tests and logs. */
    var allocated: Int = 0
        private set

    /** A buffer of at least [size] bytes: a kept one that fits, else a new one. */
    @Synchronized
    fun take(size: Int): ByteArray {
        val index = free.indexOfFirst { it.size >= size }
        if (index >= 0) return free.removeAt(index)
        allocated++
        return ByteArray(sized(size))
    }

    /** [buffer] may be reused. Buffers too small for a picture (a configuration's) are dropped. */
    @Synchronized
    fun give(buffer: ByteArray) {
        if (buffer.size < MIN_KEPT_BYTES || free.any { it === buffer }) return
        if (free.size < maxKept) {
            free += buffer
        } else {
            // Full: keep the larger buffers, which fit every picture the smaller ones fit.
            val smallest = free.indices.minBy { free[it].size }
            if (free[smallest].size < buffer.size) free[smallest] = buffer
        }
    }

    private companion object {
        const val MAX_KEPT = 8
        const val MIN_KEPT_BYTES = 4 * 1024
        const val GRAIN = 16 * 1024
        const val HEADROOM_PARTS = 8

        /** [size] plus an eighth, rounded up to 16 KiB. */
        fun sized(size: Int): Int {
            val wanted = size.toLong() + size / HEADROOM_PARTS
            return (((wanted + GRAIN - 1) / GRAIN) * GRAIN).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
}
