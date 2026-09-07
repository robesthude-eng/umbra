package com.umbra.app.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.PUT
import com.umbra.app.BuildConfig

// ---------- DTO (зеркало контракта сервера, см. docs/api.md) ----------

@Serializable
data class RegisterRequest(
    val username: String,
    val identity_ed25519: String,
    val identity_x25519: String,
    val signed_prekey: String,
    val signed_prekey_signature: String,
    val one_time_prekeys: List<String>,
    val key_version: Int = 1,
    val registration_id: Int = 1,
    val signed_prekey_id: Int = 1,
    val one_time_prekey_ids: List<Int> = emptyList(),
    val key_bundle_id: String = "",
)

@Serializable
data class RegisterResponse(val id: String, val username: String)

@Serializable
data class ChallengeRequest(val username: String)

@Serializable
data class ChallengeResponse(val challenge: String)

@Serializable
data class VerifyRequest(val username: String, val challenge: String, val signature: String)

@Serializable
data class VerifyResponse(val token: String, val expires_at: String)

@Serializable
data class AccountResponse(val id: String, val username: String, val created_at: String = "",
    val key_version: Int = 1, val one_time_prekey_count: Int = 100)

@Serializable
data class PreKeyBundle(
    val id: String,
    val username: String,
    val identity_ed25519: String,
    val identity_x25519: String,
    val signed_prekey: String,
    val signed_prekey_signature: String,
    val one_time_prekey: String,
    val key_version: Int = 1,
    val registration_id: Int = 1,
    val signed_prekey_id: Int = 1,
    val one_time_prekey_id: Int = 0,
    val device_id: Int = 1,
)

@Serializable
data class SendMessageRequest(
    val recipient_id: String,
    val ciphertext: String,
    val expires_in: Long? = null,
    val client_message_id: String? = null,
)

@Serializable
data class MessageDto(
    val id: String,
    val sender_id: String,
    val recipient_id: String = "",
    val chat_id: String = "",
    val ciphertext: String,
    val created_at: String,
    val expires_at: String? = null,
    val client_message_id: String? = null,
)

@Serializable
data class MessagesResponse(val messages: List<MessageDto>)

@Serializable
data class ChatDto(
    val id: String,
    val type: String,
    val title: String,
    val created_by: String,
    val created_at: String,
)

@Serializable
data class ChatsResponse(val chats: List<ChatDto>)

@Serializable
data class CreateChatRequest(val title: String)

@Serializable
data class SendChatMessageRequest(val ciphertext: String, val expires_in: Long? = null)

@Serializable
data class AddMemberRequest(val user_id: String)

@Serializable
data class InitiateCallRequest(val callee_id: String, val video: Boolean = false)

@Serializable
data class CallStatusRequest(val status: String)

@Serializable
data class ContactRequest(val contact_id: String)

@Serializable
data class ContactsResponse(val contacts: List<String>)

// ---------- Retrofit-интерфейс ----------

interface UmbraApi {
    @POST("/v1/auth/logout")
    suspend fun logout(@Header("Authorization") auth: String): Unit

    @PUT("/v1/account/keys")
    suspend fun updateKeys(@Header("Authorization") auth: String, @Body body: RegisterRequest): Unit

    @POST("/v1/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @POST("/v1/auth/challenge")
    suspend fun challenge(@Body body: ChallengeRequest): ChallengeResponse

    @POST("/v1/auth/verify")
    suspend fun verify(@Body body: VerifyRequest): VerifyResponse

    @GET("/v1/account")
    suspend fun account(@Header("Authorization") auth: String): AccountResponse

    @GET("/v1/users/{username}/prekeys")
    suspend fun prekeys(@Path("username") username: String): PreKeyBundle

    @POST("/v1/messages")
    suspend fun sendMessage(@Header("Authorization") auth: String, @Body body: SendMessageRequest): MessageDto

    @GET("/v1/messages")
    suspend fun messages(@Header("Authorization") auth: String, @Query("since") since: String? = null,
        @Query("after_id") afterId: String? = null, @Query("limit") limit: Int = 200): MessagesResponse

    @GET("/v1/chats")
    suspend fun chats(@Header("Authorization") auth: String): ChatsResponse

    @POST("/v1/groups")
    suspend fun createGroup(@Header("Authorization") auth: String, @Body body: CreateChatRequest): ChatDto

    @POST("/v1/channels")
    suspend fun createChannel(@Header("Authorization") auth: String, @Body body: CreateChatRequest): ChatDto

    @POST("/v1/chats/{id}/members")
    suspend fun addMember(@Header("Authorization") auth: String, @Path("id") id: String, @Body body: AddMemberRequest): Unit

    @POST("/v1/chats/{id}/messages")
    suspend fun sendChatMessage(@Header("Authorization") auth: String, @Path("id") id: String, @Body body: SendChatMessageRequest): MessageDto

    @POST("/v1/calls")
    suspend fun initiateCall(@Header("Authorization") auth: String, @Body body: InitiateCallRequest): CallDto

    @POST("/v1/calls/{id}/status")
    suspend fun updateCallStatus(@Header("Authorization") auth: String, @Path("id") id: String, @Body body: CallStatusRequest): Unit

    @POST("/v1/contacts")
    suspend fun addContact(@Header("Authorization") auth: String, @Body body: ContactRequest): Unit

    @GET("/v1/contacts")
    suspend fun contacts(@Header("Authorization") auth: String): ContactsResponse

    @POST("/v1/account/burn")
    suspend fun burnAccount(@Header("Authorization") auth: String): Unit
}

@Serializable
data class CallDto(
    val id: String,
    val caller_id: String,
    val callee_id: String,
    val video: Boolean,
    val status: String,
    val created_at: String,
    val ended_at: String? = null,
)

// ---------- фабрика Retrofit ----------

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun createUmbraApi(baseUrl: String): UmbraApi {
    val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }
    val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .build()
    return Retrofit.Builder()
        .baseUrl(baseUrl.ensureTrailingSlash())
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(UmbraApi::class.java)
}

private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"
