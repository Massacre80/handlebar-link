package com.abid.handlebarlink.garage

import androidx.annotation.StringRes
import com.abid.handlebarlink.R
import java.text.NumberFormat
import java.util.Locale

/** A reminder's name ("PUC certificate"); a custom reminder has its own (Due.name). */
@StringRes
fun reminderName(kind: ReminderKind): Int =
    when (kind) {
        ReminderKind.SERVICE -> R.string.garage_next_service
        ReminderKind.CHAIN -> R.string.garage_chain
        ReminderKind.INSURANCE -> R.string.garage_insurance
        ReminderKind.PUC -> R.string.garage_puc
        ReminderKind.RC -> R.string.garage_rc
        ReminderKind.CUSTOM -> R.string.garage_custom
    }

/** What a log entry was ("General service"). */
@StringRes
fun serviceKindName(kind: ServiceKind): Int =
    when (kind) {
        ServiceKind.GENERAL -> R.string.garage_kind_general
        ServiceKind.OIL -> R.string.garage_kind_oil
        ServiceKind.CHAIN -> R.string.garage_kind_chain
        ServiceKind.TYRES -> R.string.garage_kind_tyres
        ServiceKind.BRAKES -> R.string.garage_kind_brakes
        ServiceKind.OTHER -> R.string.garage_kind_other
    }

/** A whole number with the locale's grouping ("15,860"). */
fun grouped(value: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)
