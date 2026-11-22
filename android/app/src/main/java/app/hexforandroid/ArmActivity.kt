package app.hexforandroid

import android.app.Activity
import android.content.Intent
import android.widget.Toast

/**
 * Invisible: the bubble opens it when Android has stopped Hex. A microphone
 * service may only start while the app is in front, so this comes forward
 * for a moment, starts it once resumed, and closes.
 */
class ArmActivity : Activity() {
    override fun onResume() {
        super.onResume()
        Dictation.init(this)
        if (Dictation.micAllowed() && ModelStore.isPresent(this)) {
            if (!DictationService.start(this)) Toast.makeText(this, "Open Hex to turn it on", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
