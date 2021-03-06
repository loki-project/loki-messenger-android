package org.session.libsignal.service.loki.utilities

import org.session.libsignal.service.api.crypto.DigestingOutputStream
import org.session.libsignal.service.internal.push.http.OutputStreamFactory
import java.io.OutputStream

/**
 * An `OutputStreamFactory` that copies the input directly to the output without modification.
 *
 * For encrypted attachments, see `AttachmentCipherOutputStreamFactory`.
 * For encrypted profiles, see `ProfileCipherOutputStreamFactory`.
 */
class PlaintextOutputStreamFactory : OutputStreamFactory {

  override fun createFor(outputStream: OutputStream?): DigestingOutputStream {
    return object : DigestingOutputStream(outputStream) { }
  }
}
