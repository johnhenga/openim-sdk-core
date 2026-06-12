package io.openim.core.sync

/**
 * Change states reported to [Syncer] notice callbacks.
 * Mirrors Go pkg/syncer/state.go (Insert/Update/Delete/Unchanged).
 */
enum class SyncState { Insert, Update, Delete, Unchanged }

/**
 * Generic local/server reconciliation, a direct port of
 * Go pkg/syncer/syncer.go Syncer.Sync:
 *
 * - server items missing locally are inserted (notice: Insert)
 * - server items differing from local are updated (notice: Update)
 * - equal items produce an Unchanged notice
 * - local items absent from the server are deleted (notice: Delete),
 *   unless [Args.skipDeletion]
 *
 * [uuid] extracts the identity key; [equal] defaults to structural equality
 * (data classes give Go's reflect.DeepEqual semantics for free).
 */
class Syncer<T : Any, V : Any>(
    private val uuid: (T) -> V,
    private val insert: suspend (server: T) -> Unit,
    private val update: suspend (server: T, local: T) -> Unit,
    private val delete: suspend (local: T) -> Unit,
    private val equal: (server: T, local: T) -> Boolean = { a, b -> a == b },
) {

    data class Args(
        val skipDeletion: Boolean = false,
        val skipNotice: Boolean = false,
    )

    suspend fun sync(
        serverData: List<T>,
        localData: List<T>,
        args: Args = Args(),
        notice: suspend (state: SyncState, server: T?, local: T?) -> Unit = { _, _, _ -> },
    ) {
        if (serverData.isEmpty() && localData.isEmpty()) return

        val localMap = localData.associateBy(uuid).toMutableMap()

        for (server in serverData) {
            val id = uuid(server)
            val local = localMap[id]

            if (local == null) {
                insert(server)
                if (!args.skipNotice) notice(SyncState.Insert, server, null)
                continue
            }

            localMap.remove(id)

            if (equal(server, local)) {
                if (!args.skipNotice) notice(SyncState.Unchanged, server, local)
                continue
            }

            update(server, local)
            if (!args.skipNotice) notice(SyncState.Update, server, local)
        }

        if (args.skipDeletion) return

        for (local in localMap.values) {
            delete(local)
            if (!args.skipNotice) notice(SyncState.Delete, null, local)
        }
    }
}
