package dev.jtiisto.wellnesssync.core.ble.polar

import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import dev.jtiisto.wellnesssync.core.database.dao.AccelerometerSummaryDao
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withTimeout
import java.time.format.DateTimeFormatter

class PolarOfflineSync(
    private val polarApi: PolarBleApi,
    private val intervalDao: IntervalDao,
    private val accDao: AccelerometerSummaryDao,
    private val parser: PolarRecordingParser,
    private val featureReadyTimeoutMs: Long = DEFAULT_FEATURE_TIMEOUT_MS,
) {

    suspend fun syncDevice(deviceId: String): PolarSyncResult {
        var intervalsFetched = 0
        var summariesFetched = 0
        var recordingsProcessed = 0
        var recordingsDeleted = 0
        val errors = mutableListOf<String>()

        try {
            // Connect and wait for offline recording feature to be ready
            connectAndWaitForFeature(deviceId)

            // List all offline recordings
            val entries = polarApi.listOfflineRecordings(deviceId).toList().await()

            if (entries.isEmpty()) {
                Log.i(TAG, "No offline recordings found on device $deviceId")
                disconnectQuietly(deviceId)
                return PolarSyncResult()
            }

            Log.i(TAG, "Found ${entries.size} recordings on device $deviceId")

            // Filter to only PPI and ACC recordings
            val relevantEntries = entries.filter {
                it.type == PolarBleApi.PolarDeviceDataType.PPI ||
                    it.type == PolarBleApi.PolarDeviceDataType.ACC
            }

            for (entry in relevantEntries) {
                try {
                    val sessionId = deriveSessionId(entry)
                    val phoneTimestamp = System.currentTimeMillis()

                    // Fetch recording data
                    val recordingData = polarApi.getOfflineRecord(deviceId, entry).await()

                    // Parse and store based on type
                    when (recordingData) {
                        is PolarOfflineRecordingData.PpiOfflineRecording -> {
                            val result = parser.parsePpi(
                                data = recordingData.data,
                                startTime = recordingData.startTime,
                                deviceId = deviceId,
                                sessionId = sessionId,
                                phoneTimestamp = phoneTimestamp,
                            )
                            intervalDao.insertAll(result.intervals)
                            intervalsFetched += result.intervals.size
                        }

                        is PolarOfflineRecordingData.AccOfflineRecording -> {
                            val summaries = parser.parseAccSummaries(
                                data = recordingData.data,
                                startTime = recordingData.startTime,
                                deviceId = deviceId,
                                sessionId = sessionId,
                            )
                            accDao.insertAll(summaries)
                            summariesFetched += summaries.size
                        }

                        else -> {
                            Log.w(TAG, "Skipping unsupported recording type: ${entry.type}")
                            continue
                        }
                    }

                    recordingsProcessed++

                    // Delete from device only after successful store
                    try {
                        polarApi.removeOfflineRecord(deviceId, entry).await()
                        recordingsDeleted++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete recording from device: ${e.message}")
                        errors.add("Delete failed for ${entry.path}: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process recording ${entry.path}: ${e.message}")
                    errors.add("Fetch/parse failed for ${entry.path}: ${e.message}")
                    // Continue to next recording — don't lose other data
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed for device $deviceId: ${e.message}")
            errors.add("Sync failed: ${e.message}")
        } finally {
            disconnectQuietly(deviceId)
        }

        return PolarSyncResult(
            intervalsFetched = intervalsFetched,
            summariesFetched = summariesFetched,
            recordingsProcessed = recordingsProcessed,
            recordingsDeleted = recordingsDeleted,
            errors = errors,
        )
    }

    private suspend fun connectAndWaitForFeature(deviceId: String) {
        val featureReady = CompletableDeferred<Unit>()

        polarApi.setApiCallback(object : PolarBleApiCallback() {
            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature,
            ) {
                if (identifier == deviceId &&
                    feature == PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING
                ) {
                    featureReady.complete(Unit)
                }
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                if (polarDeviceInfo.deviceId == deviceId && !featureReady.isCompleted) {
                    featureReady.completeExceptionally(
                        PolarSyncException("Device disconnected before feature ready")
                    )
                }
            }

            override fun disInformationReceived(identifier: String, uuid: java.util.UUID, value: String) {}

            override fun disInformationReceived(
                identifier: String,
                disInfo: com.polar.androidcommunications.api.ble.model.DisInfo,
            ) {}

            override fun htsNotificationReceived(
                identifier: String,
                data: com.polar.sdk.api.model.PolarHealthThermometerData,
            ) {}
        })

        polarApi.connectToDevice(deviceId)

        withTimeout(featureReadyTimeoutMs) {
            featureReady.await()
        }
    }

    private fun disconnectQuietly(deviceId: String) {
        try {
            polarApi.disconnectFromDevice(deviceId)
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting from $deviceId: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PolarOfflineSync"
        const val DEFAULT_FEATURE_TIMEOUT_MS = 30_000L
        private val SESSION_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

        fun deriveSessionId(entry: PolarOfflineRecordingEntry): String {
            val dateStr = entry.date.format(SESSION_DATE_FORMAT)
            return "polar_${entry.type.name.lowercase()}_$dateStr"
        }
    }
}

class PolarSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
