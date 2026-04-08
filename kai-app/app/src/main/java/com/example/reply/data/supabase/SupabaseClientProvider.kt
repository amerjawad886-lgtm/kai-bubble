package com.example.reply.data.supabase

import android.content.Context
import com.example.reply.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SupabaseRestClient(
    context: Context
) {
    private val baseUrl: String = BuildConfig.SUPABASE_URL.trim().removeSuffix("/")
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY.trim()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNullSafe()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun isConfigured(): Boolean {
        return baseUrl.startsWith("http") && anonKey.isNotBlank()
    }

    suspend fun insert(
        table: String,
        body: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false

        val url = "$baseUrl/rest/v1/$table"
        val request = baseRequest(url)
            .addHeader("Prefer", "return=minimal")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        executeSuccess(request)
    }

    suspend fun upsert(
        table: String,
        body: JSONObject,
        onConflict: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false

        val url = "$baseUrl/rest/v1/$table?on_conflict=${encode(onConflict)}"
        val request = baseRequest(url)
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        executeSuccess(request)
    }

    suspend fun selectArray(
        tableOrView: String,
        select: String = "*",
        filters: Map<String, String> = emptyMap()
    ): JSONArray = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext JSONArray()

        val params = linkedMapOf("select" to select)
        params.putAll(filters)

        val query = params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }

        val url = "$baseUrl/rest/v1/$tableOrView?$query"
        val request = baseRequest(url)
            .get()
            .build()

        executeArray(request)
    }

    private fun baseRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .addHeader("Accept", "application/json")
    }

    private fun executeSuccess(request: Request): Boolean {
        return runCatching {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    private fun executeArray(request: Request): JSONArray {
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use JSONArray()
                val body = response.body?.string().orEmpty().trim()
                if (body.isBlank()) JSONArray() else JSONArray(body)
            }
        }.getOrDefault(JSONArray())
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun String.toMediaTypeOrNullSafe() = this.toMediaType()
}

object SupabaseClientProvider {
    @Volatile
    private var instance: SupabaseRestClient? = null

    fun get(context: Context): SupabaseRestClient {
        return instance ?: synchronized(this) {
            instance ?: SupabaseRestClient(context.applicationContext).also { instance = it }
        }
    }
}