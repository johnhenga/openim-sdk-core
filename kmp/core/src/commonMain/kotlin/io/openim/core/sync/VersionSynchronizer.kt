package io.openim.core.sync

/**
 * Local version-sync state, one row per (tableName, entityID) in
 * `local_sync_version`. Mirrors Go model_struct.LocalVersionSync; [idList]
 * is persisted in the `id_list` TEXT column (JSON-encoded, as GORM does).
 */
data class VersionSyncState(
    val tableName: String,
    val entityID: String,
    val versionID: String = "",
    val version: Long = 0,
    val createTime: Long = 0,
    val idList: List<String> = emptyList(),
)

/** Persistence for [VersionSyncState] (Go: db VersionSyncModel). */
interface VersionSyncStore {
    suspend fun get(tableName: String, entityID: String): VersionSyncState?
    suspend fun set(state: VersionSyncState)
}

/**
 * Incremental synchronizer for versioned collections (friends, joined
 * groups, group members, conversations): a faithful port of
 * pkg/syncer/version_synchronizer.go IncrementalSync.
 *
 * The server is asked for changes since the locally stored
 * (versionID, version) and replies with delete keys, updates, inserts, and
 * a `full` flag when the client is too far behind to patch incrementally:
 *
 * - nothing changed and not full: return without touching the version row
 * - full: run [fullSyncer], rebuild the id list from [fullIDs]
 * - otherwise: maintain the id list (drop deleted keys, append new ones),
 *   build the expected server state as local ⊕ changes ⊖ deletions, and
 *   reconcile via the generic [Syncer] (which performs the DB writes and
 *   emits insert/update/delete notices)
 *
 * The new (versionID, version, idList) is persisted at the end.
 */
class VersionSynchronizer<V : Any>(
    private val tableName: String,
    private val entityID: String,
    private val versionStore: VersionSyncStore,
    private val key: (V) -> String,
    private val local: suspend () -> List<V>,
    private val server: suspend (VersionSyncState?) -> Response<V>,
    private val syncer: Syncer<V, String>,
    private val fullSyncer: suspend () -> Unit,
    private val fullIDs: suspend () -> List<String>,
    private val notice: suspend (state: SyncState, server: V?, local: V?) -> Unit = { _, _, _ -> },
) {

    /** Server reply to an incremental request (Go: the resp accessors). */
    data class Response<V>(
        val versionID: String,
        val version: Long,
        val full: Boolean,
        val deleteKeys: List<String> = emptyList(),
        val updates: List<V> = emptyList(),
        val inserts: List<V> = emptyList(),
        /** Go: IDOrderChanged — e.g. friend reorder or role-level change. */
        val idOrderChanged: Boolean = false,
    )

    suspend fun incrementalSync() {
        val stored = versionStore.get(tableName, entityID)
        val resp = server(stored)

        val changes = resp.updates + resp.inserts
        if (resp.deleteKeys.isEmpty() && changes.isEmpty() && !resp.full) {
            return // Go parity: version row left untouched
        }

        var idList = stored?.idList ?: emptyList()

        if (resp.full) {
            fullSyncer()
            idList = fullIDs()
        } else {
            if (resp.deleteKeys.isNotEmpty()) {
                idList = idList - resp.deleteKeys.toSet()
            }
            val newKeys = changes.map(key).filter { it !in idList }
            if (newKeys.isNotEmpty()) idList = idList + newKeys

            val localData = local()
            val expected = LinkedHashMap<String, V>(localData.size + changes.size)
            for (v in localData) expected[key(v)] = v
            for (change in changes) expected[key(change)] = change
            for (id in resp.deleteKeys) expected.remove(id)

            syncer.sync(expected.values.toList(), localData, notice = notice)

            if (resp.idOrderChanged) {
                idList = fullIDs()
            }
        }

        versionStore.set(
            VersionSyncState(
                tableName = tableName,
                entityID = entityID,
                versionID = resp.versionID,
                version = resp.version,
                createTime = stored?.createTime ?: 0,
                idList = idList,
            )
        )
    }
}
