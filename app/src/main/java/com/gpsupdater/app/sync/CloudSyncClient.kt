package com.gpsupdater.app.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CloudSyncClient(
    context: Context,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshots = BackupSnapshotRepository(context.applicationContext)

    fun backup(configuration: CloudSyncConfiguration, callback: (Result<Unit>) -> Unit) {
        execute(callback) {
            validate(configuration)
            backupWithConflictResolution(configuration)
        }
    }

    fun restore(configuration: CloudSyncConfiguration, callback: (Result<Unit>) -> Unit) {
        execute(callback) {
            validate(configuration)
            val encrypted = request(configuration, "GET", null).body
            val snapshot = BackupSnapshotCodec.decode(
                CloudCipher.decrypt(encrypted, configuration.syncKey),
            )
            snapshots.merge(snapshot)
        }
    }

    fun deleteBackup(configuration: CloudSyncConfiguration, callback: (Result<Unit>) -> Unit) {
        execute(callback) {
            validate(configuration)
            request(configuration, "DELETE", null)
            Unit
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun request(
        configuration: CloudSyncConfiguration,
        method: String,
        requestBody: String?,
        requestHeaders: Map<String, String> = emptyMap(),
    ): SyncHttpResponse {
        val url = URL(
            "${configuration.endpoint.trimEnd('/')}/v1/sync/${CloudCipher.syncId(configuration.syncKey)}",
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty(
                "Authorization",
                "Bearer ${CloudCipher.authorization(configuration.syncKey)}",
            )
            connection.setRequestProperty("Accept", "application/json")
            requestHeaders.forEach(connection::setRequestProperty)
            if (requestBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter().use { it.write(requestBody) }
            }
            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                throw SyncHttpException(responseCode, responseBody)
            }
            return SyncHttpResponse(
                body = responseBody,
                etag = connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun backupWithConflictResolution(configuration: CloudSyncConfiguration) {
        CloudBackupCoordinator(
            readRemote = {
                try {
                    request(configuration, "GET", null).let { response ->
                        RemoteEncryptedSnapshot(response.body, response.etag)
                    }
                } catch (error: SyncHttpException) {
                    if (error.statusCode == HTTP_NOT_FOUND) null else throw error
                }
            },
            mergeRemote = { encrypted ->
                snapshots.merge(
                    BackupSnapshotCodec.decode(
                        CloudCipher.decrypt(encrypted, configuration.syncKey),
                    ),
                )
            },
            createEncryptedSnapshot = {
                CloudCipher.encrypt(
                    BackupSnapshotCodec.encode(snapshots.export()),
                    configuration.syncKey,
                )
            },
            writeRemote = { encrypted, condition ->
                val headers = when (condition) {
                    SnapshotWriteCondition.CreateOnly -> mapOf("If-None-Match" to "*")
                    SnapshotWriteCondition.Unconditional -> emptyMap()
                    is SnapshotWriteCondition.IfMatch -> mapOf("If-Match" to condition.etag)
                }
                try {
                    request(configuration, "PUT", encrypted, headers)
                } catch (error: SyncHttpException) {
                    if (error.statusCode == HTTP_PRECONDITION_FAILED) {
                        throw SnapshotConflictException()
                    }
                    throw error
                }
            },
        ).backup()
    }

    private fun validate(configuration: CloudSyncConfiguration) {
        require(configuration.endpoint.startsWith("https://")) { "HTTPS endpoint required" }
        require(configuration.syncKey.length >= MIN_SYNC_KEY_LENGTH) { "Invalid sync key" }
    }

    private fun <T> execute(callback: (Result<T>) -> Unit, operation: () -> T) {
        executor.execute {
            val result = runCatching(operation)
            mainHandler.post { callback(result) }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MIN_SYNC_KEY_LENGTH = 20
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PRECONDITION_FAILED = 412
    }
}

private data class SyncHttpResponse(
    val body: String,
    val etag: String?,
)

private class SyncHttpException(
    val statusCode: Int,
    responseBody: String,
) : IOException("Sync server returned HTTP $statusCode: $responseBody")
