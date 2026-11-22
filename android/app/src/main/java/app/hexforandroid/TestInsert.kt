package app.hexforandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A developer hook: inserts text into the focused field exactly as a finished
 * dictation would, so insertion can be tested without speaking. Only callers
 * holding android.permission.DUMP (adb's shell) can send it:
 *
 *     adb shell "am broadcast -a app.hexforandroid.TEST_INSERT -n app.hexforandroid/.TestInsert --es text 'hello there'"
 */
class TestInsert : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        Delivery.deliver(context, text)
    }
}
