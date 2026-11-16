package com.abid.handlebarlink.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreBikeSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.settings(file: File) =
        DataStoreBikeSettings(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

    @Test
    fun `selects, reads back and forgets the bike`() =
        runTest {
            val settings = settings(folder.newFile("bike.preferences_pb").also { it.delete() })
            val bike = SelectedBike("D0:B2:70:42:7F:FB", "HONDA BTU 2040")

            assertThat(settings.bike.first()).isNull()
            settings.select(bike)
            assertThat(settings.bike.first()).isEqualTo(bike)
            settings.forget()
            assertThat(settings.bike.first()).isNull()
        }

    @Test
    fun `the selection survives a new instance on the same file`() =
        runTest {
            val file = folder.newFile("bike.preferences_pb").also { it.delete() }
            val bike = SelectedBike("D0:B2:70:42:7F:FB", "HONDA BTU 2040")
            // DataStore allows one active instance per file: close the first, like an app restart.
            val firstRun = CoroutineScope(backgroundScope.coroutineContext + Job())
            DataStoreBikeSettings(PreferenceDataStoreFactory.create(scope = firstRun) { file }).select(bike)
            // DataStore releases the file when its scope has completed, not when cancel() returns.
            firstRun.coroutineContext.job.cancelAndJoin()

            val reopened = DataStoreBikeSettings(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

            assertThat(reopened.bike.first()).isEqualTo(bike)
        }
}
