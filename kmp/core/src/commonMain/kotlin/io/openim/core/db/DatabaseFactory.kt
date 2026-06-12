package io.openim.core.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Opens (or creates) the per-user database. The file name convention is
 * identical to the Go SDK (pkg/db/db_init.go initDB):
 * `OpenIM_<bigVersion>_<loginUserID>.db` inside [dbDir] — this is what makes
 * in-place upgrades from the Go SDK work.
 */
expect class DriverFactory {
    fun createDriver(dbDir: String, loginUserID: String): SqlDriver
}

/** Go: pkg/constant BigVersion. */
const val BIG_VERSION = "v3"

fun databaseFileName(loginUserID: String): String =
    "OpenIM_${BIG_VERSION}_$loginUserID.db"

/**
 * Opens the database and ensures the static schema exists, using the verbatim
 * Go SDK DDL (see [GoSdkSchema]) for both fresh installs and upgrades.
 */
fun openDatabase(driver: SqlDriver): OpenIMDatabase {
    GoSdkSchema.createIfNotExists(driver)
    return OpenIMDatabase(driver)
}

/**
 * Passed to platform drivers in place of the SQLDelight-generated schema:
 * creation and migration are owned by [GoSdkSchema] so the on-disk schema
 * stays byte-identical to the Go SDK's.
 */
object NoopSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Value(Unit)
    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
}
