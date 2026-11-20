package com.abid.handlebarlink.ui.setup

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.abid.handlebarlink.R
import com.abid.handlebarlink.ui.permissions.Access
import org.junit.Test

/** Setup's last step, "You're set": only the notifications since ride logging was dropped (owner, 2026-11-20). */
class SetupAccessTest {
    private var asked = 0
    private val actions = SetupActions({}, {}, {}, {}, {}, {}, onNotifications = { asked++ }, onHome = {})

    @Test
    fun `You're set asks for the notifications only, not for location`() {
        val rows = SetupAccess(Access.ASK).rows(actions)

        assertThat(rows.map { it.title }).containsExactly(R.string.permission_notifications)
        rows.single().onAct()
        assertThat(asked == 1).isTrue()
    }

    @Test
    fun `Home opens once the notifications are allowed`() {
        assertThat(SetupAccess(Access.GRANTED).complete).isTrue()
        assertThat(SetupAccess(Access.ASK).complete).isFalse()
    }

    @Test
    fun `notifications refused for good let the rider continue without them`() {
        assertThat(SetupAccess(Access.BLOCKED).blocked).isTrue()
        assertThat(SetupAccess(Access.ASK).blocked).isFalse()
    }
}
