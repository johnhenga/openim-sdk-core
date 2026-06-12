package io.openim.core.network

/**
 * ReqIdentifier values routing requests/pushes over the websocket envelope.
 * Mirror of the ws block in pkg/constant/constant.go.
 */
object ReqIdentifier {
    // client -> server requests
    const val GET_NEWEST_SEQ = 1001
    const val PULL_MSG_BY_RANGE = 1002
    const val SEND_MSG = 1003
    const val SEND_SIGNAL_MSG = 1004
    const val PULL_MSG_BY_SEQ_LIST = 1005
    const val GET_CONV_MAX_READ_SEQ = 1006
    const val PULL_CONV_LAST_MESSAGE = 1007

    // server -> client pushes
    const val PUSH_MSG = 2001
    const val KICK_ONLINE_MSG = 2002
    const val LOGOUT_MSG = 2003
    const val SET_BACKGROUND_STATUS = 2004
    const val WS_SUB_USER_ONLINE_STATUS = 2005
}
