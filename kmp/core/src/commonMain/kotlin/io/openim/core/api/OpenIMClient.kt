package io.openim.core.api

import io.openim.core.network.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The modern typed API surface of the SDK (design doc §6.1).
 *
 * This is the contract the Kotlin core implements module by module during
 * Phase 3; the legacy JSON-string facade in [io.openim.core.api.compat]
 * delegates here. Method groups map 1:1 to the Go public API in
 * open_im_sdk/ (134 functions); they are added as their backing modules are
 * ported — keeping this interface honest about what actually works.
 */
interface OpenIMClient {

    // ---- lifecycle (Go: open_im_sdk init.go) ----
    suspend fun login(userID: String, token: String)
    suspend fun logout()
    val loginStatus: StateFlow<LoginStatus>

    // ---- connection events (Go: OnConnListener) ----
    val connectionState: StateFlow<ConnectionState>

    // ---- event streams replacing the Go listener interfaces ----
    val conversationEvents: Flow<ConversationEvent>
    val messageEvents: Flow<MessageEvent>
    val friendshipEvents: Flow<FriendshipEvent>
    val groupEvents: Flow<GroupEvent>
}

enum class LoginStatus { Empty, Logging, Logged, Logout }

/** Go: OnConversationListener methods become one sealed event stream. */
sealed interface ConversationEvent {
    data object SyncServerStart : ConversationEvent
    data object SyncServerFinish : ConversationEvent
    data object SyncServerFailed : ConversationEvent
    data class NewConversation(val conversationIDs: List<String>) : ConversationEvent
    data class ConversationChanged(val conversationIDs: List<String>) : ConversationEvent
    data class TotalUnreadCountChanged(val count: Long) : ConversationEvent
}

/** Go: OnAdvancedMsgListener methods. */
sealed interface MessageEvent {
    data class NewReceived(val messageJson: String) : MessageEvent
    data class ReadReceipt(val receiptJson: String) : MessageEvent
    data class Revoked(val revokedJson: String) : MessageEvent
    data class Deleted(val messageJson: String) : MessageEvent
}

/** Go: OnFriendshipListener methods. */
sealed interface FriendshipEvent {
    data class FriendAdded(val friendJson: String) : FriendshipEvent
    data class FriendDeleted(val friendJson: String) : FriendshipEvent
    data class FriendInfoChanged(val friendJson: String) : FriendshipEvent
    data class ApplicationAdded(val applicationJson: String) : FriendshipEvent
    data class ApplicationAccepted(val applicationJson: String) : FriendshipEvent
    data class ApplicationRejected(val applicationJson: String) : FriendshipEvent
    data class BlackAdded(val blackJson: String) : FriendshipEvent
    data class BlackDeleted(val blackJson: String) : FriendshipEvent
}

/** Go: OnGroupListener methods. */
sealed interface GroupEvent {
    data class JoinedGroupAdded(val groupJson: String) : GroupEvent
    data class JoinedGroupDeleted(val groupJson: String) : GroupEvent
    data class GroupInfoChanged(val groupJson: String) : GroupEvent
    data class MemberAdded(val memberJson: String) : GroupEvent
    data class MemberDeleted(val memberJson: String) : GroupEvent
    data class MemberInfoChanged(val memberJson: String) : GroupEvent
    data class ApplicationAdded(val applicationJson: String) : GroupEvent
    data class ApplicationAccepted(val applicationJson: String) : GroupEvent
    data class ApplicationRejected(val applicationJson: String) : GroupEvent
    data object GroupDismissed : GroupEvent
}
