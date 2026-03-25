package com.example.aiassistant.data

import androidx.preference.PreferenceManager
import com.example.aiassistant.config.AppConfig

//负责网络层
class ChatRepository {
    private val apiService = RetrofitClient.instance

    suspend fun getCompletion(request: ChatCompletionRequest): Result<ChatCompletionResponse> {
        return try {
            val apiKey = AppConfig.apiKey
            val authHeader = if (apiKey.startsWith("Bearer ")) apiKey else "Bearer $apiKey"
            val response = apiService.createChatCompletion(authHeader, request)
            Result.success(response)
        } catch (e: Exception) {
            // 捕获网络或解析异常
            Result.failure(e)
        }
    }
}