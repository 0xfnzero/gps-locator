package com.gpsupdater.app.sync

import java.io.IOException

internal data class RemoteEncryptedSnapshot(
    val body: String,
    val etag: String?,
)

internal sealed interface SnapshotWriteCondition {
    data object CreateOnly : SnapshotWriteCondition
    data object Unconditional : SnapshotWriteCondition
    data class IfMatch(val etag: String) : SnapshotWriteCondition
}

internal class SnapshotConflictException : IOException("Sync snapshot changed during backup")

internal class CloudBackupCoordinator(
    private val readRemote: () -> RemoteEncryptedSnapshot?,
    private val mergeRemote: (String) -> Unit,
    private val createEncryptedSnapshot: () -> String,
    private val writeRemote: (String, SnapshotWriteCondition) -> Unit,
) {
    fun backup() {
        repeat(MAX_BACKUP_ATTEMPTS) {
            val remote = readRemote()
            remote?.let { mergeRemote(it.body) }
            val condition = when {
                remote == null -> SnapshotWriteCondition.CreateOnly
                remote.etag != null -> SnapshotWriteCondition.IfMatch(remote.etag)
                else -> SnapshotWriteCondition.Unconditional
            }
            try {
                writeRemote(createEncryptedSnapshot(), condition)
                return
            } catch (_: SnapshotConflictException) {
                // The next attempt reads and merges the winning snapshot.
            }
        }
        throw IOException("Sync snapshot changed repeatedly during backup")
    }

    companion object {
        private const val MAX_BACKUP_ATTEMPTS = 3
    }
}
