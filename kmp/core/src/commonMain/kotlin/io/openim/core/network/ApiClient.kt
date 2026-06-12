package io.openim.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Server error from an HTTP API call (Go: ApiResponse non-zero ErrCode). */
class ApiException(val errCode: Int, val errMsg: String, val errDlt: String) :
    Exception("api error $errCode: $errMsg ($errDlt)")

/**
 * HTTP API client mirroring pkg/network/http_client.go ApiPost: JSON body,
 * `operationID` and `token` headers, and the standard response envelope
 * `{errCode, errMsg, errDlt, data}` with the payload under `data`.
 */
class ApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val token: () -> String,
    private val operationID: () -> String = WsMsgSyncTransport.Companion::generateOperationID,
) {

    @Serializable
    private data class Envelope(
        val errCode: Int = 0,
        val errMsg: String = "",
        val errDlt: String = "",
        val data: JsonElement? = null,
    )

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun <Req, Resp> post(
        route: String,
        req: Req,
        reqSerializer: KSerializer<Req>,
        respSerializer: KSerializer<Resp>,
    ): Resp {
        val response = httpClient.post(baseUrl.trimEnd('/') + route) {
            contentType(ContentType.Application.Json)
            header("operationID", operationID())
            header("token", token())
            setBody(json.encodeToString(reqSerializer, req))
        }
        val envelope = json.decodeFromString<Envelope>(response.bodyAsText())
        if (envelope.errCode != 0) {
            throw ApiException(envelope.errCode, envelope.errMsg, envelope.errDlt)
        }
        val data = envelope.data
            ?: return json.decodeFromString(respSerializer, "{}")
        return json.decodeFromJsonElement(respSerializer, data)
    }
}

/** API routes mirrored from pkg/api/api.go (added as modules are ported). */
object ApiRoutes {
    const val GET_INCREMENTAL_FRIENDS = "/friend/get_incremental_friends"
    const val GET_FULL_FRIEND_USER_IDS = "/friend/get_full_friend_user_ids"
}
