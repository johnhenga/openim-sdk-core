package io.openim.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

actual class DriverFactory {
    actual fun createDriver(dbDir: String, loginUserID: String): SqlDriver {
        return NativeSqliteDriver(
            DatabaseConfiguration(
                name = databaseFileName(loginUserID),
                version = 1,
                create = { /* schema is created by GoSdkSchema, not SQLDelight */ },
                upgrade = { _, _, _ -> },
                extendedConfig = DatabaseConfiguration.Extended(basePath = dbDir),
            )
        )
    }
}
