package com.abid.handlebarlink.devices

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.abid.handlebarlink.controls.RiderActions
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The intercom's assistant button. Android starts this for `ACTION_VOICE_COMMAND` when a headset asks for
 * the voice assistant (HFP AT+BVRA); it does what the rider chose on the Devices page and closes at once,
 * with nothing on screen (a translucent theme; also over the lock screen while riding). Exported for the
 * system; only that action is acted on, and no extras are read.
 */
@AndroidEntryPoint
class AssistantButtonActivity : ComponentActivity() {
    @Inject lateinit var button: AssistantButton

    @Inject lateinit var actions: RiderActions

    @Inject lateinit var logger: Logger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_VOICE_COMMAND) {
            finish()
            return
        }
        lifecycleScope.launch {
            val action = button.action.first()
            logger.child("assistant").info("assistant.pressed", "action" to action.name)
            actions.perform(action)
            finish()
        }
    }
}
