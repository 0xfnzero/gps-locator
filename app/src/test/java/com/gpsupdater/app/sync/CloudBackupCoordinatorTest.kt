package com.gpsupdater.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CloudBackupCoordinatorTest {
    @Test
    fun backup_retriesAndMergesTheWinningSnapshotAfterConflict() {
        var remote = RemoteEncryptedSnapshot("remote-v1", "etag-v1")
        val merged = mutableListOf<String>()
        val conditions = mutableListOf<SnapshotWriteCondition>()
        var writes = 0
        val coordinator = CloudBackupCoordinator(
            readRemote = { remote },
            mergeRemote = merged::add,
            createEncryptedSnapshot = { "local-after-${merged.last()}" },
            writeRemote = { _, condition ->
                conditions += condition
                writes += 1
                if (writes == 1) {
                    remote = RemoteEncryptedSnapshot("remote-v2", "etag-v2")
                    throw SnapshotConflictException()
                }
            },
        )

        coordinator.backup()

        assertEquals(listOf("remote-v1", "remote-v2"), merged)
        assertEquals(
            listOf(
                SnapshotWriteCondition.IfMatch("etag-v1"),
                SnapshotWriteCondition.IfMatch("etag-v2"),
            ),
            conditions,
        )
    }

    @Test
    fun backup_usesCreateOnlyForAnEmptyRemoteAndSupportsLegacyServers() {
        val conditions = mutableListOf<SnapshotWriteCondition>()
        CloudBackupCoordinator(
            readRemote = { null },
            mergeRemote = {},
            createEncryptedSnapshot = { "first" },
            writeRemote = { _, condition -> conditions += condition },
        ).backup()
        CloudBackupCoordinator(
            readRemote = { RemoteEncryptedSnapshot("legacy", null) },
            mergeRemote = {},
            createEncryptedSnapshot = { "updated" },
            writeRemote = { _, condition -> conditions += condition },
        ).backup()

        assertEquals(
            listOf(SnapshotWriteCondition.CreateOnly, SnapshotWriteCondition.Unconditional),
            conditions,
        )
    }

    @Test
    fun backup_failsAfterThreeConsecutiveConflicts() {
        val coordinator = CloudBackupCoordinator(
            readRemote = { RemoteEncryptedSnapshot("remote", "etag") },
            mergeRemote = {},
            createEncryptedSnapshot = { "local" },
            writeRemote = { _, _ -> throw SnapshotConflictException() },
        )

        assertThrows(IOException::class.java) { coordinator.backup() }
    }
}
