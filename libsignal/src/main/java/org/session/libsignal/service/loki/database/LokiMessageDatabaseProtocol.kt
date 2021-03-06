package org.session.libsignal.service.loki.database

interface LokiMessageDatabaseProtocol {

    fun getQuoteServerID(quoteID: Long, quoteePublicKey: String): Long?
    fun setServerID(messageID: Long, serverID: Long)
}
