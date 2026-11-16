package com.abid.handlebarlink.core.designsystem.preview

import androidx.compose.ui.tooling.preview.Preview

/**
 * The nine screen sizes the testing-setup skill asks every screen to be checked at: compact, medium and
 * expanded widths (400, 610, 900 dp) by heights 400, 500 and 1000 dp.
 */
@Preview(name = "400x400", widthDp = 400, heightDp = 400)
@Preview(name = "400x500", widthDp = 400, heightDp = 500)
@Preview(name = "400x1000", widthDp = 400, heightDp = 1000)
@Preview(name = "610x400", widthDp = 610, heightDp = 400)
@Preview(name = "610x500", widthDp = 610, heightDp = 500)
@Preview(name = "610x1000", widthDp = 610, heightDp = 1000)
@Preview(name = "900x400", widthDp = 900, heightDp = 400)
@Preview(name = "900x500", widthDp = 900, heightDp = 500)
@Preview(name = "900x1000", widthDp = 900, heightDp = 1000)
public annotation class ScreenSizes

/** A phone (400x500) at the user's largest common text size (font scale 1.5), per the testing-setup skill. */
@Preview(name = "400x500 large text", widthDp = 400, heightDp = 500, fontScale = 1.5f)
public annotation class LargeText

/**
 * A component at normal and large text (font scale 1 and 1.5). Preview names contain no "." (the screenshot
 * tool cuts file names at the first dot, so "font 1.5" variants overwrote each other).
 */
@Preview(name = "normal text", widthDp = 411)
@Preview(name = "large text", widthDp = 411, fontScale = 1.5f)
public annotation class FontScales
