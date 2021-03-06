package org.session.libsession.messaging.sending_receiving.notifications

import android.annotation.SuppressLint
import nl.komponents.kovenant.functional.map
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.session.libsession.messaging.MessagingConfiguration
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.service.loki.api.onionrequests.OnionRequestAPI
import org.session.libsignal.service.loki.utilities.retryIfNeeded
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.logging.Log

@SuppressLint("StaticFieldLeak")
object PushNotificationAPI {
    val context = MessagingConfiguration.shared.context
    val server = "https://live.apns.getsession.org"
    val serverPublicKey = "642a6585919742e5a2d4dc51244964fbcd8bcab2b75612407de58b810740d049"
    private val maxRetryCount = 4
    private val tokenExpirationInterval = 12 * 60 * 60 * 1000

    enum class ClosedGroupOperation {
        Subscribe, Unsubscribe;

        val rawValue: String
            get() {
                return when (this) {
                    Subscribe -> "subscribe_closed_group"
                    Unsubscribe -> "unsubscribe_closed_group"
                }
            }
    }

    fun unregister(token: String) {
        val parameters = mapOf( "token" to token )
        val url = "$server/unregister"
        val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body)
        retryIfNeeded(maxRetryCount) {
            OnionRequestAPI.sendOnionRequest(request.build(), server, serverPublicKey, "/loki/v2/lsrpc").map { json ->
                val code = json["code"] as? Int
                if (code != null && code != 0) {
                    TextSecurePreferences.setIsUsingFCM(context, false)
                } else {
                    Log.d("Loki", "Couldn't disable FCM due to error: ${json["message"] as? String ?: "null"}.")
                }
            }.fail { exception ->
                Log.d("Loki", "Couldn't disable FCM due to error: ${exception}.")
            }
        }
        // Unsubscribe from all closed groups
        val allClosedGroupPublicKeys = MessagingConfiguration.shared.storage.getAllClosedGroupPublicKeys()
        val userPublicKey = MessagingConfiguration.shared.storage.getUserPublicKey()!!
        allClosedGroupPublicKeys.forEach { closedGroup ->
            performOperation(ClosedGroupOperation.Unsubscribe, closedGroup, userPublicKey)
        }
    }

    fun register(token: String, publicKey: String, force: Boolean) {
        val oldToken = TextSecurePreferences.getFCMToken(context)
        val lastUploadDate = TextSecurePreferences.getLastFCMUploadTime(context)
        if (!force && token == oldToken && System.currentTimeMillis() - lastUploadDate < tokenExpirationInterval) { return }
        val parameters = mapOf( "token" to token, "pubKey" to publicKey )
        val url = "$server/register"
        val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body)
        retryIfNeeded(maxRetryCount) {
            OnionRequestAPI.sendOnionRequest(request.build(), server, serverPublicKey, "/loki/v2/lsrpc").map { json ->
                val code = json["code"] as? Int
                if (code != null && code != 0) {
                    TextSecurePreferences.setIsUsingFCM(context, true)
                    TextSecurePreferences.setFCMToken(context, token)
                    TextSecurePreferences.setLastFCMUploadTime(context, System.currentTimeMillis())
                } else {
                    Log.d("Loki", "Couldn't register for FCM due to error: ${json["message"] as? String ?: "null"}.")
                }
            }.fail { exception ->
                Log.d("Loki", "Couldn't register for FCM due to error: ${exception}.")
            }
        }
        // Subscribe to all closed groups
        val allClosedGroupPublicKeys = MessagingConfiguration.shared.storage.getAllClosedGroupPublicKeys()
        allClosedGroupPublicKeys.forEach { closedGroup ->
            performOperation(ClosedGroupOperation.Subscribe, closedGroup, publicKey)
        }
    }

    fun performOperation(operation: ClosedGroupOperation, closedGroupPublicKey: String, publicKey: String) {
        if (!TextSecurePreferences.isUsingFCM(context)) { return }
        val parameters = mapOf( "closedGroupPublicKey" to closedGroupPublicKey, "pubKey" to publicKey )
        val url = "$server/${operation.rawValue}"
        val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body)
        retryIfNeeded(maxRetryCount) {
            OnionRequestAPI.sendOnionRequest(request.build(), server, serverPublicKey, "/loki/v2/lsrpc").map { json ->
                val code = json["code"] as? Int
                if (code == null || code == 0) {
                    Log.d("Loki", "Couldn't subscribe/unsubscribe closed group: $closedGroupPublicKey due to error: ${json["message"] as? String ?: "null"}.")
                }
            }.fail { exception ->
                Log.d("Loki", "Couldn't subscribe/unsubscribe closed group: $closedGroupPublicKey due to error: ${exception}.")
            }
        }
    }
}
