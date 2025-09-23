package com.noke.nokeapidemo

import android.util.Log
import com.noke.nokemobilelibrary.NokeDevice
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * Demo Web Client for making requests to demo server and show API implementation
 */
class DemoWebClient {

    var serverUrl: String = "CLIENT_URL_HERE"
    private var demoWebClientCallback: DemoWebClientCallback? = null

    @Suppress("UNUSED_PARAMETER")
    fun requestUnlock(noke: NokeDevice, email: String) {
        // This method mirrors the legacy demo behaviour. Requests should not be made to the Core API directly
        thread {
            try {
                val jsonObject = JSONObject().apply {
                    accumulate("session", noke.session)
                    accumulate("mac", noke.mac)
                }

                val url = "${serverUrl}unlock/"
                Log.w(TAG, "JSON: $jsonObject")
                demoWebClientCallback?.onUnlockReceived(post(url, jsonObject), noke)
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to request unlock", exception)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun requestUnshackle(noke: NokeDevice, email: String) {
        thread {
            try {
                val jsonObject = JSONObject().apply {
                    accumulate("session", noke.session)
                    accumulate("mac", noke.mac)
                }

                val url = "${serverUrl}unshackle/"
                demoWebClientCallback?.onUnlockReceived(post(url, jsonObject), noke)
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to request unshackle", exception)
            }
        }
    }

    fun requestFobSync(noke: NokeDevice) {
        thread {
            try {
                val jsonObject = JSONObject().apply {
                    accumulate("session", noke.session)
                    accumulate("mac", noke.mac)
                }

                val url = "${serverUrl}fobs/sync/"
                demoWebClientCallback?.onUnlockReceived(post(url, jsonObject), noke)
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to request fob sync", exception)
            }
        }
    }

    fun setWebClientCallback(callback: DemoWebClientCallback?) {
        demoWebClientCallback = callback
    }

    interface DemoWebClientCallback {
        fun onUnlockReceived(response: String, noke: NokeDevice)
    }

    companion object {
        private val TAG = DemoWebClient::class.java.simpleName

        @Throws(IOException::class)
        private fun convertInputStreamToString(inputStream: InputStream): String {
            BufferedReader(InputStreamReader(inputStream)).use { bufferedReader ->
                val result = StringBuilder()
                var line: String?
                while (bufferedReader.readLine().also { line = it } != null) {
                    result.append(line)
                }
                return result.toString()
            }
        }

        private fun post(urlStr: String, jsonObject: JSONObject): String {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            System.setProperty("http.keepAlive", "false")
            Log.w(TAG, "JSON: $jsonObject")

            return try {
                val url = URL(urlStr)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    val sslContext = SSLContext.getInstance("TLS").apply {
                        init(null, null, java.security.SecureRandom())
                    }
                    if (this is javax.net.ssl.HttpsURLConnection) {
                        sslSocketFactory = sslContext.socketFactory
                    }
                    readTimeout = 60000
                    connectTimeout = 10000
                    requestMethod = "POST"
                    doInput = true
                    useCaches = false
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Connection", "close")
                    setRequestProperty("charset", "utf-8")
                }

                DataOutputStream(connection.outputStream).use { stream ->
                    stream.writeBytes(jsonObject.toString())
                    stream.flush()
                }

                connection.connect()
                inputStream = connection.inputStream
                inputStream?.let { convertInputStreamToString(it) } ?: "Did not work!"
            } catch (exception: IOException) {
                Log.e(TAG, "Network request failed", exception)
                ""
            } catch (exception: NoSuchAlgorithmException) {
                Log.e(TAG, "TLS configuration failed", exception)
                ""
            } catch (exception: KeyManagementException) {
                Log.e(TAG, "TLS configuration failed", exception)
                ""
            } finally {
                inputStream?.close()
                connection?.disconnect()
            }
        }
    }
}
