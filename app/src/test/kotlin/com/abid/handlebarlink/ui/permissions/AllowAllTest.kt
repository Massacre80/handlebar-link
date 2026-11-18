package com.abid.handlebarlink.ui.permissions

import android.content.Intent
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import org.junit.Test

class AllowAllTest {
    private val nearby = AccessRequest.Runtime(listOf("nearby"))
    private val notifications = AccessRequest.Runtime(listOf("notifications"))
    private val listener = AccessRequest.Screen { Intent() }
    private val overlay = AccessRequest.Screen { Intent() }

    @Test
    fun `every missing runtime permission is asked together first`() {
        val items =
            listOf(Access.ASK to nearby, Access.ASK to listener, Access.BLOCKED to notifications, Access.ASK to overlay)
        assertThat(nextToAllow(items, emptySet())).containsExactly(0, 2)
    }

    @Test
    fun `then one special access at a time, in order`() {
        val items = listOf(Access.GRANTED to nearby, Access.ASK to listener, Access.ASK to overlay)
        assertThat(nextToAllow(items, emptySet())).containsExactly(1)
        assertThat(nextToAllow(items, setOf(1))).containsExactly(2)
    }

    @Test
    fun `a refused item is not asked again in the same walk, and the walk ends`() {
        val items = listOf(Access.ASK to nearby, Access.ASK to listener)
        assertThat(nextToAllow(items, setOf(0, 1))).isEmpty()
    }

    @Test
    fun `nothing to ask when all are allowed`() {
        val items = listOf(Access.GRANTED to nearby, Access.GRANTED to listener, Access.GRANTED to AccessRequest.None)
        assertThat(nextToAllow(items, emptySet())).isEmpty()
    }
}
