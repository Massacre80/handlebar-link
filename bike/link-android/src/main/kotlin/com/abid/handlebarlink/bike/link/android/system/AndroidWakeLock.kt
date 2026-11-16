package com.abid.handlebarlink.bike.link.android.system

import android.content.Context
import android.os.PowerManager
import com.abid.handlebarlink.bike.link.spi.WakeLockPort

/** A partial wake lock for the meter setup only, always with a timeout (edge case 12). */
internal class AndroidWakeLock(
    context: Context,
) : WakeLockPort {
    private val lock =
        context
            .getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HandlebarLink:meter-setup")
            .apply { setReferenceCounted(false) }

    override fun acquire(timeoutMillis: Long) {
        lock.acquire(timeoutMillis)
    }

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}
