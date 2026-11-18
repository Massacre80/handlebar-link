package com.abid.handlebarlink.ui.controls

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.controls.Unavailable
import com.abid.handlebarlink.controls.allowedActions
import org.junit.Test

class ActionChoicesTest {
    private fun choices(situation: CallSituation) = allowedActions(situation).map { ActionChoice(it) }

    @Test
    fun `on a call the chooser shows Call, Sound and Screen, Nothing on its own (Paper Handlebar 5)`() {
        val grouped = groupedChoices(choices(CallSituation.ON_CALL))

        assertThat(grouped.map { it.first }).containsExactly(ActionGroup.CALL, ActionGroup.SOUND, ActionGroup.SCREEN)
        assertThat(grouped.first().second.map { it.action })
            .containsExactly(RiderAction.END_CALL, RiderAction.MUTE_MIC)
        assertThat(grouped.flatMap { it.second }.map { it.action }.contains(RiderAction.NOTHING)).isFalse()
    }

    @Test
    fun `every choice but Nothing has a group, so none goes missing from the chooser`() {
        val all = RiderAction.entries.map { ActionChoice(it) }

        assertThat(groupedChoices(all).sumOf { it.second.size }).isEqualTo(all.size - 1)
    }

    @Test
    fun `soon means needs our own CarPlay, not any other reason`() {
        assertThat(ActionChoice(RiderAction.SIRI, Unavailable.NEEDS_OUR_CARPLAY).soon).isTrue()
        assertThat(ActionChoice(RiderAction.MUTE_MIC, Unavailable.MIC_REFUSED).soon).isFalse()
        assertThat(ActionChoice(RiderAction.NEXT).soon).isFalse()
    }

    @Test
    fun `the intercom's assistant button shows Siri as soon but not choosable`() {
        val siri = ASSISTANT_CHOICES.first { it.action == RiderAction.SIRI }

        assertThat(siri.soon).isTrue()
        assertThat(siri.choosable).isFalse()
        assertThat(ASSISTANT_CHOICES.filter { !it.choosable }.map { it.action }).containsExactly(RiderAction.SIRI)
    }
}
