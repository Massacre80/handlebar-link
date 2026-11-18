package com.abid.handlebarlink.controls

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
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

class DataStoreHandlebarMappingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun newFile(): File = folder.newFile("handlebar.preferences_pb").also { it.delete() }

    private fun TestScope.store(
        file: File,
        scope: CoroutineScope = backgroundScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }

    private fun TestScope.mapping(
        store: DataStore<Preferences>,
        scope: CoroutineScope = backgroundScope,
    ) = DataStoreHandlebarMapping(store, scope, testLogger(RecordingLogSink(), TestTimeProvider(testScheduler)))

    /** The mapping, once [switch] does [action] in [situation]. */
    private suspend fun HandlebarMapping.awaitAction(
        situation: CallSituation,
        switch: HandlebarSwitch,
        action: RiderAction,
    ) = current.first { it.getValue(situation)[switch] == action }

    @Test
    fun `each situation's choices survive a new instance on the same file`() =
        runTest {
            val file = newFile()
            // DataStore allows one active instance per file: close the first, like an app restart (lesson 18).
            val firstRun = CoroutineScope(backgroundScope.coroutineContext + Job())
            val first = mapping(store(file, firstRun), firstRun)
            first.assign(CallSituation.RIDING, HandlebarSwitch.LEFT, RiderAction.BATTERY)
            first.assign(CallSituation.RINGING, HandlebarSwitch.UP, RiderAction.ANSWER_CALL)
            first.assign(CallSituation.ON_CALL, HandlebarSwitch.LEFT, RiderAction.END_CALL)
            firstRun.coroutineContext.job.cancelAndJoin()

            val reopened = mapping(store(file))
            val read = reopened.awaitAction(CallSituation.ON_CALL, HandlebarSwitch.LEFT, RiderAction.END_CALL)

            assertThat(read.getValue(CallSituation.RIDING)[HandlebarSwitch.LEFT]).isEqualTo(RiderAction.BATTERY)
            assertThat(read.getValue(CallSituation.RINGING)[HandlebarSwitch.UP]).isEqualTo(RiderAction.ANSWER_CALL)
            // The same press in another situation keeps its own default.
            assertThat(read.getValue(CallSituation.RINGING)[HandlebarSwitch.LEFT]).isEqualTo(RiderAction.ANSWER_CALL)
        }

    @Test
    fun `a rider's setting from before situations is read as the riding layer`() =
        runTest {
            val store = store(newFile())
            // Written under the key the app used before situations existed.
            store.edit { it[stringPreferencesKey("handlebar_LEFT")] = "BATTERY" }

            val read = mapping(store).awaitAction(CallSituation.RIDING, HandlebarSwitch.LEFT, RiderAction.BATTERY)

            assertThat(read.getValue(CallSituation.ON_CALL)[HandlebarSwitch.LEFT]).isEqualTo(RiderAction.MUTE_MIC)
        }

    @Test
    fun `a riding choice is stored under the old key`() =
        runTest {
            val store = store(newFile())

            mapping(store).assign(CallSituation.RIDING, HandlebarSwitch.UP, RiderAction.TIME)

            assertThat(store.data.first()[stringPreferencesKey("handlebar_UP")]).isEqualTo("TIME")
        }

    @Test
    fun `an action its situation does not offer is refused and nothing is stored`() =
        runTest {
            val store = store(newFile())
            val mapping = mapping(store)

            val musicOnCall = mapping.assign(CallSituation.ON_CALL, HandlebarSwitch.RIGHT, RiderAction.NEXT)
            val answerWhileRiding = mapping.assign(CallSituation.RIDING, HandlebarSwitch.RIGHT, RiderAction.ANSWER_CALL)

            assertThat(musicOnCall).isFalse()
            assertThat(answerWhileRiding).isFalse()
            assertThat(store.data.first().asMap()).isEqualTo(emptyMap())
        }

    @Test
    fun `an offered action is accepted`() =
        runTest {
            val mapping = mapping(store(newFile()))

            assertThat(mapping.assign(CallSituation.ON_CALL, HandlebarSwitch.RIGHT, RiderAction.END_CALL)).isTrue()
            mapping.awaitAction(CallSituation.ON_CALL, HandlebarSwitch.RIGHT, RiderAction.END_CALL)
        }

    @Test
    fun `reset clears every situation`() =
        runTest {
            val store = store(newFile())
            val mapping = mapping(store)
            mapping.assign(CallSituation.RIDING, HandlebarSwitch.LEFT, RiderAction.BATTERY)
            mapping.assign(CallSituation.RINGING, HandlebarSwitch.UP, RiderAction.ANSWER_CALL)
            mapping.assign(CallSituation.ON_CALL, HandlebarSwitch.LEFT, RiderAction.END_CALL)

            mapping.reset()

            assertThat(store.data.first()[stringPreferencesKey("handlebar_LEFT")]).isNull()
            assertThat(mapping.current.first { it == HANDLEBAR_DEFAULTS }).isEqualTo(HANDLEBAR_DEFAULTS)
            assertThat(store.data.first().asMap()).isEqualTo(emptyMap())
        }
}
