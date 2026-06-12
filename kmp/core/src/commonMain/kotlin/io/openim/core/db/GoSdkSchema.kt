package io.openim.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Verbatim DDL of the SQLite schema written by the Go SDK
 * (GORM AutoMigrate, see pkg/db/db_init.go versionDataMigrate).
 *
 * Fresh databases MUST be created from these statements — not from the
 * SQLDelight-generated Schema — so that new installs are byte-identical to
 * databases written by the Go SDK, and a single code path serves both fresh
 * installs and in-place upgrades.
 *
 * Source of truth: docs/schema/go-sdk-schema.sql, regenerated with
 * `go run ./tools/schemagen`. The schema-compat CI test asserts these strings
 * match that file.
 */
object GoSdkSchema {

    val tables: List<String> = listOf(
        "CREATE TABLE `local_app_sdk_version` (`version` varchar(255),`installed` numeric,PRIMARY KEY (`version`))",
        "CREATE TABLE `local_blacks` (`owner_user_id` varchar(64),`block_user_id` varchar(64),`nickname` varchar(255),`face_url` varchar(255),`create_time` integer,`add_source` integer,`operator_user_id` varchar(64),`ex` varchar(1024),`attached_info` varchar(1024),PRIMARY KEY (`owner_user_id`,`block_user_id`))",
        "CREATE TABLE `local_chat_log_reaction_extensions` (`client_msg_id` char(64),`local_reaction_extensions` blob,PRIMARY KEY (`client_msg_id`))",
        "CREATE TABLE `local_chat_logs` (`client_msg_id` char(64),`server_msg_id` char(64),`send_id` char(64),`recv_id` char(64),`sender_platform_id` integer,`sender_nick_name` varchar(255),`sender_face_url` varchar(255),`session_type` integer,`msg_from` integer,`content_type` integer,`content` varchar(1000),`is_read` numeric,`status` integer,`seq` integer DEFAULT 0,`send_time` integer,`create_time` integer,`attached_info` varchar(1024),`ex` varchar(1024),`local_ex` varchar(1024),PRIMARY KEY (`client_msg_id`))",
        "CREATE TABLE `local_conversations` (`conversation_id` char(128),`conversation_type` integer,`user_id` char(64),`group_id` char(128),`show_name` varchar(255),`face_url` varchar(255),`recv_msg_opt` integer,`unread_count` integer,`group_at_type` integer,`latest_msg` varchar(1000),`latest_msg_send_time` integer,`draft_text` text,`draft_text_time` integer,`is_pinned` numeric,`is_private_chat` numeric,`burn_duration` integer DEFAULT 30,`is_not_in_group` numeric,`update_unread_count_time` integer,`attached_info` varchar(1024),`ex` varchar(1024),`max_seq` integer,`min_seq` integer,`msg_destruct_time` integer DEFAULT 604800,`is_msg_destruct` numeric DEFAULT false,PRIMARY KEY (`conversation_id`))",
        "CREATE TABLE `local_friends` (`owner_user_id` varchar(64),`friend_user_id` varchar(64),`remark` varchar(255),`create_time` integer,`add_source` integer,`operator_user_id` varchar(64),`name` varchar(255),`face_url` varchar(255),`ex` varchar(1024),`attached_info` varchar(1024),`is_pinned` numeric,PRIMARY KEY (`owner_user_id`,`friend_user_id`))",
        "CREATE TABLE `local_group_members` (`group_id` varchar(64),`user_id` varchar(64),`nickname` varchar(255),`user_group_face_url` varchar(255),`role_level` integer,`join_time` integer,`join_source` integer,`inviter_user_id` text,`mute_end_time` integer DEFAULT 0,`operator_user_id` varchar(64),`ex` varchar(1024),`attached_info` varchar(1024),PRIMARY KEY (`group_id`,`user_id`))",
        "CREATE TABLE `local_groups` (`group_id` varchar(64),`name` text,`notification` varchar(255),`introduction` varchar(255),`face_url` varchar(255),`create_time` integer,`status` integer,`creator_user_id` varchar(64),`group_type` integer,`owner_user_id` varchar(64),`member_count` integer,`ex` varchar(1024),`attached_info` varchar(1024),`need_verification` integer,`look_member_info` integer,`apply_member_friend` integer,`notification_update_time` integer,`notification_user_id` text,PRIMARY KEY (`group_id`))",
        "CREATE TABLE `local_notification_seqs` (`conversation_id` char(128),`seq` integer,PRIMARY KEY (`conversation_id`))",
        "CREATE TABLE `local_sending_messages` (`conversation_id` char(128),`client_msg_id` char(64),`ex` varchar(1024),PRIMARY KEY (`conversation_id`,`client_msg_id`))",
        "CREATE TABLE `local_stranger` (`user_id` varchar(64),`name` varchar(255),`face_url` varchar(255),`create_time` integer,`app_manger_level` integer,`ex` varchar(1024),`attached_info` varchar(1024),`global_recv_msg_opt` integer,PRIMARY KEY (`user_id`))",
        "CREATE TABLE `local_sync_version` (`table_name` varchar(255),`entity_id` varchar(255),`version_id` text,`version` integer,`create_time` integer,`id_list` text,PRIMARY KEY (`table_name`,`entity_id`))",
        "CREATE TABLE `local_uploads` (`part_hash` text,`upload_id` varchar(1000),`upload_info` varchar(2000),`expire_time` integer,`create_time` integer,PRIMARY KEY (`part_hash`))",
        "CREATE TABLE `local_users` (`user_id` varchar(64),`name` varchar(255),`face_url` varchar(255),`create_time` integer,`app_manger_level` integer,`ex` varchar(1024),`attached_info` varchar(1024),`global_recv_msg_opt` integer,PRIMARY KEY (`user_id`))",
    )

    val indexes: List<String> = listOf(
        "CREATE INDEX `content_type_alone` ON `local_chat_logs`(`content_type`)",
        "CREATE INDEX `index_join_time` ON `local_group_members`(`join_time`)",
        "CREATE INDEX `index_latest_msg_send_time` ON `local_conversations`(`latest_msg_send_time`)",
        "CREATE INDEX `index_recv_id` ON `local_chat_logs`(`recv_id`)",
        "CREATE INDEX `index_role_level` ON `local_group_members`(`role_level`)",
        "CREATE INDEX `index_send_time` ON `local_chat_logs`(`send_time`)",
        "CREATE INDEX `index_seq` ON `local_chat_logs`(`seq`)",
    )

    /**
     * Creates any missing tables and indexes. Safe to call on every open:
     * a fresh database gets the full schema; a database written by the Go SDK
     * is left untouched.
     *
     * Missing objects are detected by name (like Go's tableChecker) rather
     * than with IF NOT EXISTS guards, because SQLite stores DDL text verbatim
     * in sqlite_master — the guard token would make Kotlin-created databases
     * textually diverge from Go-created ones.
     */
    fun createIfNotExists(driver: SqlDriver) {
        val existing = existingObjects(driver)
        (tables + indexes).forEach { ddl ->
            if (objectName(ddl) !in existing) {
                driver.execute(identifier = null, sql = ddl, parameters = 0)
            }
        }
    }

    fun existingTables(driver: SqlDriver): Set<String> =
        existingNames(driver, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")

    private fun existingObjects(driver: SqlDriver): Set<String> =
        existingNames(driver, "SELECT name FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%'")

    private fun existingNames(driver: SqlDriver, sql: String): Set<String> =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.next().value) {
                    cursor.getString(0)?.let(names::add)
                }
                QueryResult.Value(names.toSet())
            },
            parameters = 0,
        ).value

    /** Extracts the backtick-quoted object name from a CREATE statement. */
    internal fun objectName(ddl: String): String =
        ddl.substringAfter('`').substringBefore('`')
}
