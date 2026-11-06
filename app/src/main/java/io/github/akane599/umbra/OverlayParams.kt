package io.github.akane599.umbra

import android.os.Build
import android.view.WindowManager

/**
 * Makes a touch-catching overlay cover the whole display: display cutouts, and the status and navigation bar areas.
 * Shared by [LockOverlay] and [TouchShield] so the two can't drift apart. Without fitInsetsTypes = 0, Android 11+
 * stops the window at the navigation bar inset (FLAG_LAYOUT_IN_SCREEN only drops the status bar from the default fit
 * types), and taps in that strip reach the app underneath.
 */
fun WindowManager.LayoutParams.coverWholeDisplay() {
    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    if (Build.VERSION.SDK_INT >= 30) {
        fitInsetsTypes = 0
        fitInsetsSides = 0
    }
}
