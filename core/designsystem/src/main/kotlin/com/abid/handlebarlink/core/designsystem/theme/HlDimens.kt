package com.abid.handlebarlink.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing tokens (`--spacing-*`). */
@Immutable
public data class HlSpacing(
    /** 4 dp. */
    val s4: Dp = 4.dp,
    /** 8 dp. */
    val s8: Dp = 8.dp,
    /** 12 dp. */
    val s12: Dp = 12.dp,
    /** 14 dp. */
    val s14: Dp = 14.dp,
    /** 16 dp: screen edges. */
    val s16: Dp = 16.dp,
    /** 20 dp. */
    val s20: Dp = 20.dp,
    /** 24 dp. */
    val s24: Dp = 24.dp,
)

/** Size tokens (`--size-*`). Interactive sizes are at least 48 dp (accessibility minimum). */
@Immutable
public data class HlSizes(
    /** Icons. */
    val icon: Dp = 24.dp,
    /** Large controls (buttons). */
    val controlLarge: Dp = 52.dp,
    /** A tab's height. */
    val tab: Dp = 52.dp,
    /** The selected tab's width. */
    val tabWidth: Dp = 74.dp,
    /** The bottom bar's height. */
    val bar: Dp = 70.dp,
    /** Icon chips in cards. */
    val chip: Dp = 36.dp,
    /** Minimum touch target. */
    val touchTarget: Dp = 48.dp,
)

/** Corner radius tokens (`--radius-*`). */
@Immutable
public data class HlRadii(
    /** Cards. */
    val card: Dp = 24.dp,
    /** Hero cards. */
    val hero: Dp = 28.dp,
    /** Pills and circles (CSS 999px). */
    val full: Dp = 999.dp,
    /** Elements nested in cards. */
    val nested: Dp = 12.dp,
    /** Avatars. */
    val avatar: Dp = 10.dp,
    /** Buttons and inputs. */
    val control: Dp = 16.dp,
)
