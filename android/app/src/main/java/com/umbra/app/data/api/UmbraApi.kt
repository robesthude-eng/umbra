package com.umbra.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import com.umbra.app.BuildConfig
import java.util.concurrent.TimeUnit

// ---------- DTO: вход по номеру с кодом из Telegram (v0.4, T1) ----------

@Serializable
data class RequestCodeRequest(val phone: String)

@Serializable
data class RequestCodeResponse(val status: String = "", @SerialName("expires_in") val expiresIn: String = "")

@Serializable
data class VerifyCodeRequest(val phone: String, val code: String)

@Serializable
data class AccountView(
    val id: String,
    val username: String = "",
    val phone: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("last_name") val lastName: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("avatar_media_id") val avatarMediaId: String = "",
)

@Serializable
data class VerifyCodeResponse(
    val token: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("new_account") val newAccount: Boolean = false,
    @SerialName("profile_complete") val profileComplete: Boolean = false,
    val account: AccountView? = null,
)

@Serializable
data class UpdateProfileRequest(
    val name: String,
    @SerialName("last_name") val lastName: String = "",
    val username: String = "",
)

@Serializable
data class ProfileResult(
    val id: String = "",
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("last_name") val lastName: String = "",
)

@Serializable
data class SetAvatarRequest(@SerialName("media_id") val mediaId: String)

/** Публичная карточка пользователя (GET /v1/users/{id}) — имя/аватар собеседника. */
@Serializable
data class UserCard(
    val id: String = "",
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("last_name") val lastName: String = "",
    @SerialName("avatar_media_id") val avatarMediaId: String = "",
    @SerialName("created_at") val createdAt: String = "",
) {
    fun fullName(): String =
        listOf(displayName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { username }
}

@Serializable
data class MediaUploadResponse(val id: String = "", @SerialName("content_type") val contentType: String = "", val size: Long = 0)

// ---------- DTO: сообщения (сервер хранит ciphertext-блоб; здесь base64 конверта) ----------

@Serializable
data class MessageDto(
    val id: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("recipient_id") val recipientId: String = "",
    @SerialName("chat_id") val chatId: String = "",
    val ciphertext: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("client_message_id") val clientMessageId: String? = null,
)

@Serializable
data class MessagesResponse(val messages: List<MessageDto> = emptyList())

@Serializable
data class SendMessageRequest(
    @SerialName("recipient_id") val recipientId: String,
    val ciphertext: String,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

// ---------- DTO: чаты/группы ----------

@Serializable
data class ChatDto(
    val id: String,
    val type: String = "",
    val title: String = "",
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class ChatsResponse(val chats: List<ChatDto> = emptyList())

@Serializable
data class CreateChatRequest(val title: String)

@Serializable
data class MemberDto(
    @SerialName("user_id") val userId: String,
    val role: String = "",
    @SerialName("joined_at") val joinedAt: String = "",
)

@Serializable
data class MembersResponse(val members: List<MemberDto> = emptyList())

@Serializable
data class AddMemberRequest(@SerialName("user_id") val userId: String)

@Serializable
data class SendChatMessageRequest(
    val ciphertext: String,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

// ---------- DTO: контакты ----------

@Serializable
data class ContactRequest(@SerialName("contact_id") val contactId: String)

@Serializable
data class ContactsResponse(val contacts: List<String> = emptyList())

@Serializable
data class DiscoverRequest(val hashes: List<String>)

@Serializable
data class DiscoveredUser(
    val id: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    val phone: String = "",
    @SerialName("phone_hash") val phoneHash: String = "",
)

@Serializable
data class DiscoverResponse(val matches: List<DiscoveredUser> = emptyList())

// ---------- DTO: звонки (сигналинг; статусный автомат) ----------

@Serializable
data class InitiateCallRequest(@SerialName("callee_id") val calleeId: String, val video: Boolean = false)

@Serializable
data class CallDto(
    val id: String,
    @SerialName("caller_id") val callerId: String = "",
    @SerialName("callee_id") val calleeId: String = "",
    val video: Boolean = false,
    val status: String = "ringing",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
data class CallsResponse(val calls: List<CallDto> = emptyList())

@Serializable
data class CallStatusRequest(val status: String)

// ---------- Retrofit ----------

interface UmbraApi {
    // Вход по номеру (OTP).
    @POST("/v1/auth/request_code")
    suspend fun requestCode(@Body body: RequestCodeRequest): RequestCodeResponse

    @POST("/v1/auth/verify_code")
    suspend fun verifyCode(@Body body: VerifyCodeRequest): VerifyCodeResponse

    @GET("/v1/account")
    suspend fun account(@Header("Authorization") auth: String): AccountView

    @POST("/v1/account/profile")
    suspend fun updateProfile(@Header("Authorization") auth: String, @Body body: UpdateProfileRequest): ProfileResult

    @POST("/v1/account/avatar")
    suspend fun setAvatar(@Header("Authorization") auth: String, @Body body: SetAvatarRequest): Unit

    @POST("/v1/auth/logout")
    suspend fun logout(@Header("Authorization") auth: String): Unit

    @POST("/v1/account/burn")
    suspend fun burnAccount(@Header("Authorization") auth: String): Unit

    @GET("/v1/users/{id}")
    suspend fun user(@Header("Authorization") auth: String, @Path("id") id: String): UserCard

    @GET("/v1/by-username/{username}")
    suspend fun userByUsername(@Header("Authorization") auth: String, @Path("username") username: String): UserCard

    // Сообщения.
    @POST("/v1/messages")
    suspend fun sendMessage(@Header("Authorization") auth: String, @Body body: SendMessageRequest): MessageDto

    @GET("/v1/messages")
    suspend fun messages(@Header("Authorization") auth: String, @Query("since") since: String? = null,
        @Query("after_id") afterId: String? = null, @Query("limit") limit: Int = 200): MessagesResponse

    // Чаты и группы.
    @POST("/v1/groups")
    suspend fun createGroup(@Header("Authorization") auth: String, @Body body: CreateChatRequest): ChatDto

    @GET("/v1/chats")
    suspend fun chats(@Header("Authorization") auth: String): ChatsResponse

    @POST("/v1/chats/{id}/members")
    suspend fun addMember(@Header("Authorization") auth: String, @Path("id") id: String, @Body body: AddMemberRequest): MemberDto

    @GET("/v1/chats/{id}/members")
    suspend fun chatMembers(@Header("Authorization") auth: String, @Path("id") id: String): MembersResponse

    @POST("/v1/chats/{id}/messages")
    suspend fun sendChatMessage(@Header("Authorization") auth: String, @Path("id") id: String, @Body body: SendChatMessageRequest): MessageDto

    // Контакты (приватный поиск по хэшам).
    @POST("/v1/contacts")
    suspend fun addContact(@Header("Authorization") auth: String, @Body body: ContactRequest): Unit

    @GET("/v1/contacts")
    suspend fun contacts(@Header("Authorization") auth: String): ContactsResponse

    @POST("/v1/contacts/discover")
    suspend fun discoverContacts(@Header("Authorization") auth: String, @Body body: DiscoverRequest): DiscoverResponse

    // Медиа (аватар, фото/файлы; файл хранится как есть — модель T1).
    @Multipart
    @POST("/v1/media")
    suspend fun uploadMedia(
        @Header("Authorization") auth: String,
        @Part file: MultipartBody.Part,
        @Part("content_type") contentType: RequestBody,
    ): Response<MediaUploadResponse>

    @Streaming
    @GET("/v1/media/{id}")
    suspend fun downloadMedia(@Header("Authorization") auth: String, @Path("id") id: String): Response<ResponseBody>

    // Звонки (статусный автомат).
    @POST("/v1/calls")
    suspend fun initiateCall(@Header("Authorization") auth: String, @Body body: InitiateCallRequest): CallDto

    @POST("/v1/calls/{id}/status")
    suspend fun updateCallStatus(@Header("Authorization") auth: String, @Path("id") id: String, @Body body: CallStatusRequest): CallDto

    @GET("/v1/calls")
    suspend fun calls(@Header("Authorization") auth: String): CallsResponse
}

// ---------- фабрика ----------

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun createUmbraApi(baseUrl: String): UmbraApi {
    val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
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
