package com.abid.handlebarlink.garage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** Where Garage's records live: read as a flow, changed in one atomic step. */
interface GarageStore {
    /** The records; [GarageData] with its defaults until the rider adds something. */
    val data: Flow<GarageData>

    /** Applies [change] to the current records atomically and stores the result. */
    suspend fun update(change: (GarageData) -> GarageData)
}

/** [GarageStore] in a typed DataStore (one JSON file, docs/PLAN.md "Garage"). */
class DataStoreGarageStore
    @Inject
    constructor(
        private val store: DataStore<GarageData>,
    ) : GarageStore {
        override val data: Flow<GarageData> = store.data

        override suspend fun update(change: (GarageData) -> GarageData) {
            store.updateData(change)
        }
    }

/**
 * Garage's file format: JSON from the data classes (the DataStore guide's kotlinx.serialization serializer). Unknown
 * keys are skipped and missing ones take their defaults, so older and newer versions read each other's files; a file
 * that cannot be read at all is reported as corrupt, which DataStore's corruption handler resets (di/GarageModule).
 */
object GarageSerializer : Serializer<GarageData> {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    override val defaultValue: GarageData = GarageData()

    override suspend fun readFrom(input: InputStream): GarageData =
        try {
            json.decodeFromString(GarageData.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Garage file unreadable", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Garage file invalid", e)
        }

    override suspend fun writeTo(
        t: GarageData,
        output: OutputStream,
    ) {
        output.write(json.encodeToString(GarageData.serializer(), t).encodeToByteArray())
    }
}
