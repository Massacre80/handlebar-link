package com.abid.handlebarlink.carplay.engine.android

import android.content.SharedPreferences
import androidx.core.content.edit

/** Small persisted strings: the seam over SharedPreferences, so the rules on top are tested on the JVM. */
internal interface Settings {
    /** The stored value of [key], or null. */
    fun string(key: String): String?

    /** Stores [value] under [key] (synchronously; call from a background thread). */
    fun put(
        key: String,
        value: String,
    )
}

/** [Settings] in the module's SharedPreferences file. */
internal class PreferenceSettings(
    private val preferences: () -> SharedPreferences,
) : Settings {
    override fun string(key: String): String? = preferences().getString(key, null)

    override fun put(
        key: String,
        value: String,
    ) {
        // commit, not apply: the stable Wi-Fi name must be on disk before the iPhone is told about it.
        preferences().edit(commit = true) { putString(key, value) }
    }
}
