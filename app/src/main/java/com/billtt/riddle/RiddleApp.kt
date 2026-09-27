package com.billtt.riddle

import android.app.Application
import android.os.Build
import com.onyx.android.sdk.rx.RxManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * App-level Onyx SDK bootstrap — required, not optional:
 *  - HiddenApiBypass lifts Android 11's non-SDK reflection restriction. The Onyx SDK
 *    talks to the firmware via reflection on hidden framework classes
 *    (android.onyx.ViewUpdateHelper.mapToRawTouchPoint etc.); without the exemption the
 *    reflective calls silently no-op, raw-touch region mapping returns a zero rect
 *    ("Empty region detected when mapping") and the pen delivers no points at all.
 *    Must run before any Onyx SDK class is touched (their static init binds the methods).
 *  - RxManager.initAppContext gives the SDK's internals an app context.
 * Recipe copied from Boox-EinkDraw's BooxEinkDrawApp (verified working on this device).
 */
class RiddleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
        runCatching { RxManager.Builder.initAppContext(this) }
    }
}
