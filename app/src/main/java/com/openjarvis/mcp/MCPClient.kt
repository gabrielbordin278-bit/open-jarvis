package com.openjarvis.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MCPClient(
    val server: MCPServer
) {
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var connected = false
    private var availableTools = emptyList<MCPTool>()

    private fun buildRequest(body: JSONObject): Request {
        return Request.Builder()
            .url(server.url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().apply {
                        put("name", "open-jarvis")
                        put("version", "1.0.0")
                    })
                })
            }

            client.newCall(buildRequest(requestBody)).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext false
                }

                val body = response.body?.string() ?: return@withContext false
                val json = JSONObject(body)

                if (json.has("error")) {
                    return@withContext false
                }

                connected = true
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun listTools(): List<MCPTool> = withContext(Dispatchers.IO) {
        if (!connected) {
            disconnect()
            return@withContext emptyList()
        }

        try {
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/list")
            }

            client.newCall(buildRequest(requestBody)).execute().use { response ->
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val toolsArray = json.optJSONObject("result")?.optJSONArray("tools")
                    ?: JSONArray()

                availableTools = (0 until toolsArray.length()).map { i ->
                    val tool = toolsArray.getJSONObject(i)
                    MCPTool(
                        name = tool.getString("name"),
                        description = tool.optString("description", ""),
                        inputSchema = tool.optJSONObject("inputSchema")
                    )
                }

                availableTools
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun callTool(toolName: String, arguments: JSONObject): String =
        withContext(Dispatchers.IO) {
            if (!connected) {
                disconnect()
                return@withContext "Error: Not connected"
            }

            try {
                val requestBody = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 3)
                    put("method", "tools/call")
                    put("params", JSONObject().apply {
                        put("name", toolName)
                        put("arguments", arguments)
                    })
                }

                client.newCall(buildRequest(requestBody)).execute().use { response ->
                    val body = response.body?.string()
                        ?: return@withContext "Error: Empty response"

                    val json = JSONObject(body)

                    if (json.has("error")) {
                        return@withContext "Error: ${json.getJSONObject("error").optString("message")}"
                    }

                    val text = json.optJSONObject("result")
                        ?.optJSONArray("content")
                        ?.optJSONObject(0)
                        ?.optString("text", "")
                        .orEmpty()

                    if (text.isNotEmpty()) text else "Tool executed"
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

    fun disconnect() {
        connected = false
        availableTools = emptyList()
    }

    fun isConnected(): Boolean = connected

    fun getToolCount(): Int = availableTools.size

    companion object {
        const val HTTP = "http"
        const val SSE = "sse"
    }
}

data class MCPServer(
    val id: String,
    val name: String,
    val url: String,
    val apiKey: String? = null,
    val enabled: Boolean = true
)

data class MCPTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject? = null
)
