package io.openim.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver

/**
 * Message row in a per-conversation chat-log table.
 *
 * Mirrors the Go model (pkg/db/model_struct LocalChatLog) and the dynamic
 * table DDL in pkg/db/chat_log_model.go initChatLog. Dynamic tables carry
 * three columns beyond the static `local_chat_logs` table: is_react,
 * is_external_extensions, msg_first_modify_time.
 */
data class ChatLog(
    val clientMsgID: String,
    val serverMsgID: String? = null,
    val sendID: String? = null,
    val recvID: String? = null,
    val senderPlatformID: Long? = null,
    val senderNickname: String? = null,
    val senderFaceURL: String? = null,
    val sessionType: Long? = null,
    val msgFrom: Long? = null,
    val contentType: Long? = null,
    val content: String? = null,
    val isRead: Boolean = false,
    val status: Long? = null,
    val seq: Long = 0,
    val sendTime: Long? = null,
    val createTime: Long? = null,
    val attachedInfo: String? = null,
    val ex: String? = null,
    val localEx: String? = null,
    val isReact: Boolean = false,
    val isExternalExtensions: Boolean = false,
    val msgFirstModifyTime: Long? = null,
)

/**
 * Storage for messages, which the Go SDK keeps in one dynamically created
 * table per conversation: `chat_logs_<conversationID>` (see
 * pkg/utils/utils.go GetConversationTableName and pkg/constant
 * ChatLogsTableNamePre).
 *
 * SQLDelight is schema-static and cannot express dynamic table names, so this
 * store issues raw statements through the [SqlDriver]. The DDL is kept
 * byte-identical to pkg/db/chat_log_model.go initChatLog so databases remain
 * interchangeable with the Go SDK.
 */
class ChatLogStore(private val driver: SqlDriver) {

    private val knownTables = mutableSetOf<String>()

    private fun tableName(conversationID: String): String {
        // conversationIDs are SDK-generated (e.g. "si_a_b", "sg_<groupID>");
        // reject anything that could break out of the quoted identifier.
        require(conversationID.none { it == '"' || it == '`' || it == ';' }) {
            "invalid conversationID: $conversationID"
        }
        return CHAT_LOGS_TABLE_NAME_PREFIX + conversationID
    }

    /** Mirror of Go initChatLog: create table + seq/send_time indexes once. */
    fun initChatLog(conversationID: String) {
        val table = tableName(conversationID)
        if (table in knownTables) return
        if (table !in GoSdkSchema.existingTables(driver)) {
            driver.execute(null, createTableSql(table), 0)
            driver.execute(
                null,
                "CREATE INDEX `index_seq_$conversationID` ON `$table` (seq)",
                0,
            )
            driver.execute(
                null,
                "CREATE INDEX `index_send_time_$conversationID` ON `$table` (send_time)",
                0,
            )
        }
        knownTables += table
    }

    fun insert(conversationID: String, msg: ChatLog) {
        initChatLog(conversationID)
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO `${tableName(conversationID)}` (
                    client_msg_id, server_msg_id, send_id, recv_id,
                    sender_platform_id, sender_nick_name, sender_face_url,
                    session_type, msg_from, content_type, content, is_read,
                    status, seq, send_time, create_time, attached_info, ex,
                    local_ex, is_react, is_external_extensions,
                    msg_first_modify_time
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
            parameters = 22,
        ) {
            bindString(0, msg.clientMsgID)
            bindString(1, msg.serverMsgID)
            bindString(2, msg.sendID)
            bindString(3, msg.recvID)
            bindLong(4, msg.senderPlatformID)
            bindString(5, msg.senderNickname)
            bindString(6, msg.senderFaceURL)
            bindLong(7, msg.sessionType)
            bindLong(8, msg.msgFrom)
            bindLong(9, msg.contentType)
            bindString(10, msg.content)
            bindLong(11, if (msg.isRead) 1 else 0)
            bindLong(12, msg.status)
            bindLong(13, msg.seq)
            bindLong(14, msg.sendTime)
            bindLong(15, msg.createTime)
            bindString(16, msg.attachedInfo)
            bindString(17, msg.ex)
            bindString(18, msg.localEx)
            bindLong(19, if (msg.isReact) 1 else 0)
            bindLong(20, if (msg.isExternalExtensions) 1 else 0)
            bindLong(21, msg.msgFirstModifyTime)
        }
    }

    fun getBySeqs(conversationID: String, seqs: List<Long>): List<ChatLog> {
        if (seqs.isEmpty()) return emptyList()
        val placeholders = seqs.joinToString(",") { "?" }
        return query(
            "SELECT * FROM `${tableName(conversationID)}` WHERE seq IN ($placeholders) ORDER BY send_time DESC",
            parameters = seqs.size,
        ) { seqs.forEachIndexed { i, seq -> bindLong(i, seq) } }
    }

    /** Mirror of Go GetMessagesByClientMsgIDs (send_time DESC ordering). */
    fun getByClientMsgIDs(conversationID: String, clientMsgIDs: List<String>): List<ChatLog> {
        if (clientMsgIDs.isEmpty()) return emptyList()
        initChatLog(conversationID)
        val placeholders = clientMsgIDs.joinToString(",") { "?" }
        return query(
            "SELECT * FROM `${tableName(conversationID)}` WHERE client_msg_id IN ($placeholders) ORDER BY send_time DESC",
            parameters = clientMsgIDs.size,
        ) { clientMsgIDs.forEachIndexed { i, id -> bindString(i, id) } }
    }

    /** Rewrites a row by client_msg_id (Go: UpdateMessage non-key columns). */
    fun update(conversationID: String, msg: ChatLog) {
        driver.execute(
            identifier = null,
            sql = """
                UPDATE `${tableName(conversationID)}` SET
                    server_msg_id = ?, send_id = ?, recv_id = ?,
                    sender_platform_id = ?, sender_nick_name = ?,
                    sender_face_url = ?, session_type = ?, msg_from = ?,
                    content_type = ?, content = ?, is_read = ?, status = ?,
                    seq = ?, send_time = ?, create_time = ?, attached_info = ?,
                    ex = ?, local_ex = ?
                WHERE client_msg_id = ?
            """.trimIndent(),
            parameters = 19,
        ) {
            bindString(0, msg.serverMsgID)
            bindString(1, msg.sendID)
            bindString(2, msg.recvID)
            bindLong(3, msg.senderPlatformID)
            bindString(4, msg.senderNickname)
            bindString(5, msg.senderFaceURL)
            bindLong(6, msg.sessionType)
            bindLong(7, msg.msgFrom)
            bindLong(8, msg.contentType)
            bindString(9, msg.content)
            bindLong(10, if (msg.isRead) 1 else 0)
            bindLong(11, msg.status)
            bindLong(12, msg.seq)
            bindLong(13, msg.sendTime)
            bindLong(14, msg.createTime)
            bindString(15, msg.attachedInfo)
            bindString(16, msg.ex)
            bindString(17, msg.localEx)
            bindString(18, msg.clientMsgID)
        }
    }

    /** Mirror of Go GetConversationNormalMsgSeq: IFNULL(max(seq), 0). */
    fun maxSeq(conversationID: String): Long {
        val table = tableName(conversationID)
        if (table !in GoSdkSchema.existingTables(driver)) return 0
        return driver.executeQuery(
            identifier = null,
            sql = "SELECT IFNULL(MAX(seq), 0) FROM `$table`",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value
    }

    fun getMessageList(
        conversationID: String,
        count: Long,
        startTime: Long,
        isReverse: Boolean,
    ): List<ChatLog> {
        val order = if (isReverse) "ASC" else "DESC"
        val cmp = if (isReverse) ">" else "<"
        return query(
            """
            SELECT * FROM `${tableName(conversationID)}`
            WHERE send_time $cmp ? ORDER BY send_time $order LIMIT ?
            """.trimIndent(),
            parameters = 2,
        ) {
            bindLong(0, startTime)
            bindLong(1, count)
        }
    }

    private fun query(
        sql: String,
        parameters: Int,
        binder: app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit = {},
    ): List<ChatLog> = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = mutableListOf<ChatLog>()
            while (cursor.next().value) {
                rows += cursor.toChatLog()
            }
            QueryResult.Value(rows.toList())
        },
        parameters = parameters,
        binders = binder,
    ).value

    private fun SqlCursor.toChatLog() = ChatLog(
        clientMsgID = getString(0)!!,
        serverMsgID = getString(1),
        sendID = getString(2),
        recvID = getString(3),
        senderPlatformID = getLong(4),
        senderNickname = getString(5),
        senderFaceURL = getString(6),
        sessionType = getLong(7),
        msgFrom = getLong(8),
        contentType = getLong(9),
        content = getString(10),
        isRead = (getLong(11) ?: 0L) != 0L,
        status = getLong(12),
        seq = getLong(13) ?: 0L,
        sendTime = getLong(14),
        createTime = getLong(15),
        attachedInfo = getString(16),
        ex = getString(17),
        localEx = getString(18),
        isReact = (getLong(19) ?: 0L) != 0L,
        isExternalExtensions = (getLong(20) ?: 0L) != 0L,
        msgFirstModifyTime = getLong(21),
    )

    companion object {
        /** Go: pkg/constant ChatLogsTableNamePre. */
        const val CHAT_LOGS_TABLE_NAME_PREFIX = "chat_logs_"

        /** Byte-identical to the DDL in pkg/db/chat_log_model.go initChatLog. */
        internal fun createTableSql(tableName: String): String = """
            CREATE TABLE "$tableName" (
                client_msg_id CHAR(64),
                server_msg_id CHAR(64),
                send_id CHAR(64),
                recv_id CHAR(64),
                sender_platform_id INTEGER,
                sender_nick_name VARCHAR(255),
                sender_face_url VARCHAR(255),
                session_type INTEGER,
                msg_from INTEGER,
                content_type INTEGER,
                content VARCHAR(1000),
                is_read NUMERIC,
                status INTEGER,
                seq INTEGER DEFAULT 0,
                send_time INTEGER,
                create_time INTEGER,
                attached_info VARCHAR(1024),
                ex VARCHAR(1024),
                local_ex VARCHAR(1024),
                is_react NUMERIC,
                is_external_extensions NUMERIC,
                msg_first_modify_time INTEGER,
                PRIMARY KEY (client_msg_id)
            )
        """.trimIndent()
    }
}
