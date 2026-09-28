package com.billtt.riddle

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.MotionEvent
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CompanionUiTest {
    @Test fun longPressMenuOpensGroupedSettings() {
        val context = RuntimeEnvironment.getApplication()
        context.noBackupFilesDir.deleteRecursively()
        context.getSharedPreferences("riddle", 0).edit().clear().putString("api_key", "test-only-not-sent").commit()
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = lifecycle.get()
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val frame = content.getChildAt(0) as FrameLayout
        val page = frame.getChildAt(0) as DiaryView
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply { x = 100f; y = 100f; pressure = 1f }
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, 0, 0)
        page.dispatchTouchEvent(down)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        val menu = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(menu)
        assertEquals(8, menu.listView.adapter.count)
        assertEquals(activity.getString(R.string.settings_title), menu.listView.adapter.getItem(7).toString())
        menu.listView.performItemClick(null, 7, 7)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val settings = ShadowAlertDialog.getLatestAlertDialog()
        assertNotSame(menu, settings)
        assertTrue(settings.isShowing)
        settings.dismiss()
        down.recycle()
        lifecycle.pause().stop().destroy()
    }
    @Test @Config(qualifiers = "vi") fun vietnameseResourcesCoverCompanionFeatures() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("Nhật ký cổ phép thuật", context.getString(R.string.role_diary))
        assertEquals("Lịch sử theo phiên chat", context.getString(R.string.history))
        assertTrue(context.getString(R.string.context_details, 2000, 32000, 2, 6).contains("Đã nén 2 lần"))
    }
}
