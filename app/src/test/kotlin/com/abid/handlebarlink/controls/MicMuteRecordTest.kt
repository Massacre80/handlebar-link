package com.abid.handlebarlink.controls

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MicMuteRecordTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the flag survives a process death and is cleared again`() =
        runTest {
            val file = folder.newFile("mic.preferences_pb").also { it.delete() }
            // DataStore allows one active instance per file: close the first, like a process death (lesson 18).
            val dead = CoroutineScope(backgroundScope.coroutineContext + Job())
            DataStoreMicMuteRecord(PreferenceDataStoreFactory.create(scope = dead) { file }).set(true)
            dead.coroutineContext.job.cancelAndJoin()

            val restarted = DataStoreMicMuteRecord(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

            assertThat(restarted.mutedByUs()).isTrue()
            restarted.set(false)
            assertThat(restarted.mutedByUs()).isFalse()
        }
}
