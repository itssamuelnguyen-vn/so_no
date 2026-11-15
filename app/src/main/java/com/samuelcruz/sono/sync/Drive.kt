package com.samuelcruz.sono.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * A file in the app's hidden Drive folder. [version] goes up by one on every change of the file.
 * [appProperties] are small private tags: `enc = 1` marks a file encrypted end-to-end (see [Crypto]).
 */
@Serializable
data class RemoteFile(
    val id: String,
    val name: String,
    val version: Long = 0,
    val modifiedTime: String? = null,
    val appProperties: Map<String, String>? = null,
) {
    val modifiedAt: Long get() = modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0
    val encrypted: Boolean get() = appProperties?.get("enc") == "1"
}

@Serializable
private data class FileList(val files: List<RemoteFile> = emptyList(), val nextPageToken: String? = null)

@Serializable
private data class About(val user: User? = null) {
    @Serializable
    data class User(val emailAddress: String? = null, val displayName: String? = null)
}

/** Thrown for HTTP errors; 401 means the access token expired or was revoked. */
class DriveException(val code: Int, message: String) : IOException("Drive $code: $message")

/**
 * The few Google Drive v3 REST calls the sync needs, with plain HttpURLConnection (no Google client library).
 * Everything lives in `appDataFolder`: a hidden folder only this app can see, which does not clutter the user's
 * Drive and counts towards the normal 15 GB.
 */
class Drive(private val token: String) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun email(): String? = withContext(Dispatchers.IO) {
        json.decodeFromString<About>(request("GET", "$API/about?fields=user(emailAddress,displayName)").text()).user?.emailAddress
    }

    suspend fun list(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<RemoteFile>()
        var page: String? = null
        do {
            val q = "spaces=appDataFolder&pageSize=1000&fields=" +
                enc("nextPageToken,files($FIELDS)") + (page?.let { "&pageToken=" + enc(it) } ?: "")
            val res = json.decodeFromString<FileList>(request("GET", "$API/files?$q").text())
            out += res.files
            page = res.nextPageToken
        } while (page != null)
        out
    }

    suspend fun download(id: String): ByteArray = withContext(Dispatchers.IO) {
        request("GET", "$API/files/$id?alt=media").use { it.inputStream.readBytes() }
    }

    suspend fun downloadTo(id: String, target: File) = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, target.name + ".part")
        request("GET", "$API/files/$id?alt=media").use { c -> tmp.outputStream().use { c.inputStream.copyTo(it) } }
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
    }

    /** New file in the hidden folder; returns it with its id and version. [encrypted] tags it `enc = 1`. */
    suspend fun create(name: String, mime: String, content: ByteArray, encrypted: Boolean = false): RemoteFile =
        upload("POST", "$UPLOAD/files", createMeta(name, encrypted), mime, content.size.toLong()) { it.write(content) }

    suspend fun createFromFile(name: String, mime: String, file: File): RemoteFile =
        upload("POST", "$UPLOAD/files", createMeta(name, false), mime, file.length()) { out -> file.inputStream().use { it.copyTo(out) } }

    /** Replaces the content of an existing file (and its `enc` tag); returns its new version. */
    suspend fun update(id: String, mime: String, content: ByteArray, encrypted: Boolean = false): RemoteFile =
        upload("PATCH", "$UPLOAD/files/$id", """{"appProperties":{"enc":${if (encrypted) "\"1\"" else "null"}}}""", mime, content.size.toLong()) {
            it.write(content)
        }

    private fun createMeta(name: String, encrypted: Boolean) =
        """{"name":${JsonPrimitive(name)},"parents":["appDataFolder"]""" + (if (encrypted) ""","appProperties":{"enc":"1"}""" else "") + "}"

    /** Metadata (JSON) and content in one multipart request. */
    private suspend fun upload(
        method: String,
        url: String,
        meta: String,
        mime: String,
        length: Long,
        write: (java.io.OutputStream) -> Unit,
    ): RemoteFile = withContext(Dispatchers.IO) {
        val boundary = "sono" + System.nanoTime()
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: $mime\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = open(method, "$url?uploadType=multipart&fields=" + enc(FIELDS))
        c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        c.doOutput = true
        c.setFixedLengthStreamingMode(head.size + length + tail.size)
        c.outputStream.use { out -> out.write(head); write(out); out.write(tail) }
        json.decodeFromString<RemoteFile>(checked(c).text())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val c = open("DELETE", "$API/files/$id")
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299 && code != 404) throw DriveException(code, "delete")
    }

    private fun open(method: String, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (method == "PATCH") {
            // HttpURLConnection has no PATCH; Google accepts the override header.
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else {
            c.requestMethod = method
        }
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun request(method: String, url: String): HttpURLConnection = checked(open(method, url))

    private fun checked(c: HttpURLConnection): HttpURLConnection {
        val code = c.responseCode
        if (code !in 200..299) {
            val msg = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty().take(300)
            c.disconnect()
            throw DriveException(code, msg)
        }
        return c
    }

    private fun HttpURLConnection.text(): String = use { it.inputStream.bufferedReader().readText() }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FIELDS = "id,name,version,modifiedTime,appProperties"
    }
}
