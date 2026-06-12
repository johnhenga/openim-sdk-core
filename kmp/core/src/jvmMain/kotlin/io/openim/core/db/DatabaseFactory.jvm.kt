package io.openim.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual class DriverFactory {
    actual fun createDriver(dbDir: String, loginUserID: String): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:$dbDir/${databaseFileName(loginUserID)}")
}
