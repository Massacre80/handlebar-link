package com.abid.handlebarlink.carplay.engine.android.video

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Surface
import com.abid.handlebarlink.core.common.log.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ScreenDecoder] on Android: [DecoderCore] and `MediaCodec`'s callbacks on one `HandlerThread` at display priority
 * (DiPlay's decode thread asks for `THREAD_PRIORITY_DISPLAY`, `AndroidMediaSink.kt` run/runOutput), never the main
 * thread. [submit] only queues and wakes that thread once ([FrameQueue] drops instead of waiting). The thread starts
 * on first use, so creating the decoder does no work (docs/LESSONS.md 60).
 */
internal class AndroidScreenDecoder(
    private val log: Logger,
    listener: ScreenDecoder.Listener,
    private val recycler: ScreenDecoder.FrameRecycler,
    private val options: ScreenDecoder.Options,
) : ScreenDecoder {
    private val queue = FrameQueue(options.maxQueuedPictures, options.maxQueuedBytes, recycler, AndroidMonotonicClock)
    private val released = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val pumpPosted = AtomicBoolean(false)

    private val thread by lazy { HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_DISPLAY).apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    private val core by lazy {
        val interval = TimeUnit.MILLISECONDS.toNanos(options.statsIntervalMillis)
        DecoderCore(AndroidDecoderPlatform(handler, log), queue, listener, AndroidMonotonicClock, log, interval)
    }

    private val pump =
        Runnable {
            pumpPosted.set(false)
            core.pump()
        }

    private val tick =
        object : Runnable {
            override fun run() {
                if (released.get()) return
                core.tick()
                handler.postDelayed(this, TICK_MILLIS)
            }
        }

    override fun configure(
        width: Int,
        height: Int,
        framesPerSecond: Int,
    ) {
        post { core.configure(Display(width, height, framesPerSecond)) }
    }

    override fun attach(surface: Surface) {
        post { core.attach(surface) }
    }

    override fun detach() {
        val done = CountDownLatch(1)
        val posted =
            post {
                try {
                    core.detach()
                } finally {
                    done.countDown()
                }
            }
        // surfaceDestroyed must not return while the decoder still draws to the surface (SurfaceView reference: touch
        // the Surface only between surfaceCreated and surfaceDestroyed); bounded, so the main thread never hangs.
        if (posted && !done.await(options.detachTimeoutMillis, TimeUnit.MILLISECONDS)) {
            log.warn("carplay.video.detach_timeout", "waited_ms" to options.detachTimeoutMillis)
        }
    }

    override fun submit(
        frame: ByteArray,
        offset: Int,
        length: Int,
        ptsMicros: Long,
        isConfig: Boolean,
    ): Boolean {
        if (released.get()) recycler.recycle(frame) // handed back, as every dropped frame is
        val queued = !released.get() && queue.submit(frame, offset, length, ptsMicros, isConfig)
        // Also after a drop: a broken chain makes the decoder thread ask for a keyframe.
        if (pumpPosted.compareAndSet(false, true) && !post(pump)) pumpPosted.set(false)
        return queued
    }

    override fun release() {
        if (released.compareAndSet(false, true) && started.get()) {
            handler.post {
                core.release()
                thread.quitSafely()
            }
        }
    }

    /**
     * Runs [block] on the decoder thread: at once when already on it, else posted. False once released. The first
     * call starts the thread and the stats tick.
     */
    private fun post(block: Runnable): Boolean {
        val live = !released.get()
        if (live && started.compareAndSet(false, true)) handler.postDelayed(tick, TICK_MILLIS)
        val here = live && Looper.myLooper() == handler.looper
        if (here) block.run()
        return here || (live && handler.post(block))
    }

    private fun post(block: () -> Unit): Boolean = post(Runnable(block))

    private companion object {
        const val THREAD_NAME = "carplay-video"
        const val TICK_MILLIS = 1_000L
    }
}
