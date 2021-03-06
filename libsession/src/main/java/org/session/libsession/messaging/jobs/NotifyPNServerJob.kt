package org.session.libsession.messaging.jobs

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import nl.komponents.kovenant.functional.map
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody

import org.session.libsession.messaging.sending_receiving.notifications.PushNotificationAPI
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.OnionRequestAPI

import org.session.libsignal.utilities.logging.Log
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.service.loki.utilities.retryIfNeeded

class NotifyPNServerJob(val message: SnodeMessage) : Job {
    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    // Settings
    override val maxFailureCount: Int = 20
    companion object {
        val KEY: String = "NotifyPNServerJob"

        //keys used for database storage purpose
        private val KEY_MESSAGE = "message"
    }

    // Running
    override fun execute() {
        val server = PushNotificationAPI.server
        val parameters = mapOf( "data" to message.data, "send_to" to message.recipient )
        val url = "${server}/notify"
        val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body)
        retryIfNeeded(4) {
            OnionRequestAPI.sendOnionRequest(request.build(), server, PushNotificationAPI.serverPublicKey, "/loki/v2/lsrpc").map { json ->
                val code = json["code"] as? Int
                if (code == null || code == 0) {
                    Log.d("Loki", "[Loki] Couldn't notify PN server due to error: ${json["message"] as? String ?: "null"}.")
                }
            }.fail { exception ->
                Log.d("Loki", "[Loki] Couldn't notify PN server due to error: $exception.")
            }
        }.success {
            handleSuccess()
        }. fail {
            handleFailure(it)
        }
    }

    private fun handleSuccess() {
        delegate?.handleJobSucceeded(this)
    }

    private fun handleFailure(error: Exception) {
        delegate?.handleJobFailed(this, error)
    }

    //database functions

    override fun serialize(): Data {
        //serialize SnodeMessage property
        val kryo = Kryo()
        kryo.isRegistrationRequired = false
        val serializedMessage = ByteArray(4096)
        val output = Output(serializedMessage)
        kryo.writeObject(output, message)
        output.close()
        return Data.Builder().putByteArray(KEY_MESSAGE, serializedMessage)
                .build();
    }

    override fun getFactoryKey(): String {
        return AttachmentDownloadJob.KEY
    }

    class Factory: Job.Factory<NotifyPNServerJob> {
        override fun create(data: Data): NotifyPNServerJob {
            val serializedMessage = data.getByteArray(KEY_MESSAGE)
            //deserialize SnodeMessage property
            val kryo = Kryo()
            val input = Input(serializedMessage)
            val message: SnodeMessage = kryo.readObject(input, SnodeMessage::class.java)
            input.close()
            return NotifyPNServerJob(message)
        }
    }
}