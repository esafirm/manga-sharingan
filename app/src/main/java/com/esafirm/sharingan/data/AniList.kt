package com.esafirm.sharingan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal client for the public AniList GraphQL API (no key needed). */
object AniList {
    private const val ENDPOINT = "https://graphql.anilist.co"

    /**
     * POSTs a GraphQL query and returns the parsed response. Network problems, HTTP errors and
     * GraphQL-level errors all surface as [IOException].
     */
    suspend fun query(gql: String, variables: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("query", gql).put("variables", variables).toString()
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optJSONObject("data") == null) throw IOException("AniList error: ${json.optJSONArray("errors")}")
            json
        } finally {
            conn.disconnect()
        }
    }
}
