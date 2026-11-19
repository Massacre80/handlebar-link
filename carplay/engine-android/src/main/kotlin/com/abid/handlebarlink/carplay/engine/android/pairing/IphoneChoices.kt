package com.abid.handlebarlink.carplay.engine.android.pairing

import android.content.Context
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import com.abid.handlebarlink.carplay.engine.android.CarPlayIphone
import com.abid.handlebarlink.carplay.engine.android.PreferenceSettings
import com.abid.handlebarlink.carplay.engine.android.Settings

/**
 * The rider's iPhone choices: the [chosen] iPhone CarPlay dials (CarPlayIphone), and the iPhones the rider
 * [forgot] in the app (upper-case addresses). Android offers apps no public way to unpair a device
 * (BluetoothDevice.removeBond is a system API), so a forgotten iPhone stays paired with the phone, but the app no
 * longer lists it and CarPlay never picks it by itself (IphoneSelector) until it is paired or chosen again.
 */
internal data class IphoneChoices(
    val chosen: String?,
    val forgot: Set<String>,
) {
    /** Whether [address] was forgotten. */
    fun forgotten(address: String): Boolean = address.uppercase() in forgot

    /** [address] chosen, and no longer forgotten. */
    fun choose(address: String): IphoneChoices = IphoneChoices(address, forgot - address.uppercase())

    /** [address] forgotten, and no longer chosen. */
    fun forget(address: String): IphoneChoices =
        IphoneChoices(chosen?.takeUnless { it.equals(address, ignoreCase = true) }, forgot + address.uppercase())

    companion object {
        /** The settings key of the forgotten iPhones (comma-separated addresses). */
        const val FORGOT_KEY = "iphone.forgotten"

        /** The forgotten addresses stored as [text]. */
        fun parseForgot(text: String?): Set<String> =
            text
                .orEmpty()
                .split(',')
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .toSet()

        /** [addresses] as stored, in a stable order. */
        fun formatForgot(addresses: Set<String>): String = addresses.sorted().joinToString(",")
    }
}

/** Where [IphoneChoices] are kept. Blocking (disk): call from a background thread. */
internal interface IphoneChoiceStore {
    fun read(): IphoneChoices

    fun write(choices: IphoneChoices)
}

/** [IphoneChoiceStore] in the module's preferences, the file CarPlay reads its choice from (IphoneSelector). */
internal class PreferenceChoiceStore(
    private val context: Context,
    private val settings: Settings,
) : IphoneChoiceStore {
    override fun read(): IphoneChoices =
        IphoneChoices(
            CarPlayIphone.chosen(context),
            IphoneChoices.parseForgot(settings.string(IphoneChoices.FORGOT_KEY)),
        )

    override fun write(choices: IphoneChoices) {
        settings.put(IphoneChoices.FORGOT_KEY, IphoneChoices.formatForgot(choices.forgot))
        CarPlayIphone.choose(context, choices.chosen)
    }

    companion object {
        /** The store over the module's preferences file. */
        fun of(context: Context): PreferenceChoiceStore {
            val app = context.applicationContext
            val preferences by lazy { app.getSharedPreferences(AndroidPlatform.PREFERENCES, Context.MODE_PRIVATE) }
            return PreferenceChoiceStore(app, PreferenceSettings { preferences })
        }
    }
}
