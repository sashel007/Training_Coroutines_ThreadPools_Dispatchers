package org.example

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

fun main(): Unit = runBlocking {
    launch {
        try {
            val postsWithAuthorsAndComments = getPosts(client).map { post ->
                async {
                    val author = getAuthor(client, post.authorId)
                    val comments = getComments(client, post.id).map { comment ->
                        async {
                            val commentAuthor = getAuthor(client, comment.authorId)
                            CommentWithAuthor(comment, commentAuthor)
                        }
                    }.awaitAll()
                    PostWithAuthorAndComments(post, author, comments)
                }
            }.awaitAll()
            println(postsWithAuthorsAndComments)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

private const val BASE_URL = "http://127.0.0.1:9999"
private val gson = Gson()
private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()

suspend fun OkHttpClient.apiCall(url: String): Response {
    return suspendCoroutine { continuation ->
        Request.Builder()
            .url(url)
            .build()
            .let(::newCall)
            .enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
    }
}

suspend fun <T> makeRequest(url: String, client: OkHttpClient, typeToken: TypeToken<T>): T =
    withContext(Dispatchers.IO) {
        client.apiCall(url)
            .let { response ->
                if (!response.isSuccessful) {
                    response.close()
                    throw RuntimeException(response.message)
                }
                val body = response.body ?: throw RuntimeException("response body is null")
                gson.fromJson(body.string(), typeToken.type)
            }
    }

suspend fun getAuthor(client: OkHttpClient, authorId: Long): Author =
    makeRequest("$BASE_URL/api/authors/$authorId", client, object : TypeToken<Author>() {})

suspend fun getPosts(client: OkHttpClient): List<Post> =
    makeRequest("$BASE_URL/api/slow/posts", client, object : TypeToken<List<Post>>() {})

suspend fun getComments(client: OkHttpClient, id: Long): List<Comment> =
    makeRequest("$BASE_URL/api/slow/posts/$id/comments", client, object : TypeToken<List<Comment>>() {})

data class PostWithAuthorAndComments(
    val post: Post,
    val author: Author,
    val comments: List<CommentWithAuthor>
)

data class CommentWithAuthor(
    val comment: Comment,
    val author: Author
)

data class Post(
    val id: Long,
    val authorId: Long,
    val content: String,
    val published: Long,
    val likedByMe: Boolean,
    val likes: Int = 0,
    var attachment: Attachment? = null,
)

data class Attachment(
    val url: String,
    val description: String,
    val type: AttachmentType,
)

data class Comment(
    val id: Long,
    val postId: Long,
    val authorId: Long,
    val content: String,
    val published: Long,
    val likedByMe: Boolean,
    val likes: Int = 0,
)

data class Author(
    val id: Long,
    val name: String,
    val avatar: String,
)

enum class AttachmentType {
    IMAGE
}