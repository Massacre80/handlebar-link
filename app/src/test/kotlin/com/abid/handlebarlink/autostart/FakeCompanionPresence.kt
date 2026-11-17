package com.abid.handlebarlink.autostart

/** [CompanionPresence] with associations the test sets; records what was observed and released, in order. */
class FakeCompanionPresence(
    var associations: List<Association> = emptyList(),
) : CompanionPresence {
    /** A call the code under test made. */
    sealed interface Call {
        data class Observe(
            val association: Association,
        ) : Call

        data class Release(
            val association: Association,
        ) : Call
    }

    val calls = mutableListOf<Call>()

    override fun associations(): List<Association> = associations

    override fun startObserving(association: Association) {
        calls += Call.Observe(association)
    }

    override fun stopObserving(association: Association) {
        calls += Call.Release(association)
    }
}
