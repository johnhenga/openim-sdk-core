package io.openim.core.sync

/**
 * Local version-sync state, one row per (tableName, entityID) in
 * `local_sync_version`. Mirrors Go model_struct.LocalVersionSync.
 */
data class VersionSyncState(
    val tableName: String,
    val entityID: String,
    val versionID: String? = null,
    val version: Long = 0,
    val createTime: Long = 0,
    val idList: String? = null,
)

/** A page of incremental changes returned by the server. */
data class VersionDelta<T>(
    val versionID: String,
    val version: Long,
    /** Server signals that the client is too far behind and must full-sync. */
    val full: Boolean,
    val inserts: List<T> = emptyList(),
    val updates: List<T> = emptyList(),
    val deleteKeys: List<String> = emptyList(),
)

/**
 * Incremental synchronizer for versioned collections (conversations, friends,
 * joined groups, group members). Port of Go
 * pkg/syncer/version_synchronizer.go:
 *
 * 1. Load local (versionID, version) for the entity.
 * 2. Ask the server for changes since that version.
 * 3. If the server's versionID differs or it flags `full`, fall back to a
 *    full sync via [Syncer.sync] against the complete server list.
 * 4. Otherwise apply inserts/updates/deletes and persist the new version.
 */
class VersionSynchronizer<T : Any>(
    private val tableName: String,
    private val entityID: String,
    private val getLocalState: suspend (tableName: String, entityID: String) -> VersionSyncState?,
    private val setLocalState: suspend (VersionSyncState) -> Unit,
    private val fetchDelta: suspend (versionID: String?, version: Long) -> VersionDelta<T>,
    private val fetchAll: suspend () -> List<T>,
    private val localList: suspend () -> List<T>,
    private val applyInsert: suspend (T) -> Unit,
    private val applyUpdate: suspend (T) -> Unit,
    private val applyDeleteByKey: suspend (String) -> Unit,
    private val fullSyncer: Syncer<T, *>,
) {

    suspend fun sync() {
        val local = getLocalState(tableName, entityID)
        val delta = fetchDelta(local?.versionID, local?.version ?: 0)

        if (delta.full || local?.versionID != delta.versionID && local != null) {
            fullSyncer.sync(serverData = fetchAll(), localData = localList())
        } else {
            delta.inserts.forEach { applyInsert(it) }
            delta.updates.forEach { applyUpdate(it) }
            delta.deleteKeys.forEach { applyDeleteByKey(it) }
        }

        setLocalState(
            VersionSyncState(
                tableName = tableName,
                entityID = entityID,
                versionID = delta.versionID,
                version = delta.version,
            )
        )
    }
}
