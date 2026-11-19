package com.abid.handlebarlink.carplay.engine.android.pairing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class IphoneChoicesTest {
    @Test
    fun `forgotten addresses are stored upper-case, sorted, and read back without blanks`() {
        val text = IphoneChoices.formatForgot(setOf("BB:00", "AA:00"))
        assertThat(text).isEqualTo("AA:00,BB:00")
        assertThat(IphoneChoices.parseForgot(" aa:00 ,,BB:00,")).isEqualTo(setOf("AA:00", "BB:00"))
        assertThat(IphoneChoices.parseForgot(null)).isEqualTo(emptySet())
        assertThat(IphoneChoices.parseForgot("")).isEqualTo(emptySet())
    }

    @Test
    fun `forget and choose undo each other, matched without case`() {
        val forgotten = IphoneChoices("aa:00", emptySet()).forget("AA:00")
        assertThat(forgotten).isEqualTo(IphoneChoices(null, setOf("AA:00")))
        assertThat(forgotten.forgotten("aa:00")).isTrue()

        val back = forgotten.choose("aa:00")
        assertThat(back).isEqualTo(IphoneChoices("aa:00", emptySet()))
        assertThat(back.forgotten("AA:00")).isFalse()
    }
}
