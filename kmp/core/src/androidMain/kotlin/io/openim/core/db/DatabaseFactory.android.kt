package io.openim.core.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(dbDir: String, loginUserID: String): SqlDriver {
        val dbFile = File(dbDir, databaseFileName(loginUserID))
        return AndroidSqliteDriver(
            schema = NoopSchema, // schema is created by GoSdkSchema, not SQLDelight
            context = context,
            name = dbFile.absolutePath,
        )
    }
}
