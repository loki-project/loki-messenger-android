package org.session.libsession.messaging.messages.control

import org.session.libsignal.utilities.logging.Log
import org.session.libsignal.service.internal.push.SignalServiceProtos

class ReadReceipt() : ControlMessage() {

    var timestamps: LongArray? = null

    companion object {
        const val TAG = "ReadReceipt"

        fun fromProto(proto: SignalServiceProtos.Content): ReadReceipt? {
            val receiptProto = proto.receiptMessage ?: return null
            if (receiptProto.type != SignalServiceProtos.ReceiptMessage.Type.READ) return null
            val timestamps = receiptProto.timestampList
            if (timestamps.isEmpty()) return null
            return ReadReceipt(timestamps = timestamps.toLongArray())
        }
    }

    //constructor
    internal constructor(timestamps: LongArray?) : this() {
        this.timestamps = timestamps
    }

    // validation
    override fun isValid(): Boolean {
        if (!super.isValid()) return false
        val timestamps = timestamps ?: return false
        if (timestamps.isNotEmpty()) { return true }
        return false
    }

    override fun toProto(): SignalServiceProtos.Content? {
        val timestamps = timestamps
        if (timestamps == null) {
            Log.w(ExpirationTimerUpdate.TAG, "Couldn't construct read receipt proto from: $this")
            return null
        }
        val receiptProto = SignalServiceProtos.ReceiptMessage.newBuilder()
        receiptProto.type = SignalServiceProtos.ReceiptMessage.Type.READ
        receiptProto.addAllTimestamp(timestamps.asIterable())
        val contentProto = SignalServiceProtos.Content.newBuilder()
        try {
            contentProto.receiptMessage = receiptProto.build()
            return contentProto.build()
        } catch (e: Exception) {
            Log.w(ExpirationTimerUpdate.TAG, "Couldn't construct read receipt proto from: $this")
            return null
        }
    }
}