package io.openim.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The load-bearing invariant: a database created by GoSdkSchema is
 * byte-identical (sqlite_master DDL) to one created by the Go SDK's GORM
 * AutoMigrate. The golden (testdata/go-sdk-schema.sql) is regenerated with
 * `go run ./tools/schemagen` from the repo root.
 */
class SchemaCompatTest {

    private fun newDriver(): Pair<JdbcSqliteDriver, File> {
        val dbFile = File.createTempFile("openim_schema", ".db").apply { delete() }
        return JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}") to dbFile
    }

    private fun dumpSchema(driver: JdbcSqliteDriver): Set<String> = driver.executeQuery(
        identifier = null,
        sql = "SELECT COALESCE(sql,'') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'",
        mapper = { cursor ->
            val out = mutableSetOf<String>()
            while (cursor.next().value) {
                cursor.getString(0)?.takeIf { it.isNotEmpty() }?.let(out::add)
            }
            QueryResult.Value(out.toSet())
        },
        parameters = 0,
    ).value

    @Test
    fun schemaIsByteIdenticalToGoSdkDump() {
        val (driver, dbFile) = newDriver()
        GoSdkSchema.createIfNotExists(driver)
        GoSdkSchema.createIfNotExists(driver) // idempotent

        val golden = File("testdata/go-sdk-schema.sql").readLines()
            .filter { it.startsWith("CREATE ") }
            .map { it.removeSuffix(";") }
            .toSet()
        val created = dumpSchema(driver)

        assertEquals(emptySet(), golden - created, "objects missing from Kotlin schema")
        assertEquals(emptySet(), created - golden, "extra objects in Kotlin schema")
        dbFile.delete()
    }

    @Test
    fun dynamicChatLogTablesMatchGoInitChatLog() {
        val (driver, dbFile) = newDriver()
        GoSdkSchema.createIfNotExists(driver)
        val store = ChatLogStore(driver)
        val conv = "si_u1_u2"
        store.insert(conv, ChatLog(clientMsgID = "m1", seq = 1, sendTime = 100, content = "hi"))
        store.insert(conv, ChatLog(clientMsgID = "m2", seq = 2, sendTime = 200, content = "yo", isRead = true))
        assertEquals(2L, store.maxSeq(conv))
        assertEquals(0L, store.maxSeq("si_none"))
        assertEquals(listOf("m2", "m1"), store.getBySeqs(conv, listOf(1L, 2L)).map { it.clientMsgID })
        assertTrue("chat_logs_$conv" in GoSdkSchema.existingTables(driver))
        dbFile.delete()
    }
}
