package com.baer.handtype.testing

import android.graphics.Bitmap

/**
 * Debug hook object for injecting test inputs during development.
 * In production builds these methods always return null / no-op.
 * Override via subclassing or companion swap only in debug instrumentation.
 */
object HandTypeDebugHooks {

    /**
     * Returns a pre-loaded scan bitmap to be used instead of launching the ML Kit scanner.
     * Returns null in production — the real camera scanner is used.
     */
    fun loadImportedScanBitmap(): Bitmap? = null
}
