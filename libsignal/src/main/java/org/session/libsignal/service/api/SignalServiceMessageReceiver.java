/*
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.session.libsignal.service.api;

import org.session.libsignal.libsignal.InvalidMessageException;
import org.session.libsignal.service.api.crypto.AttachmentCipherInputStream;
import org.session.libsignal.service.api.crypto.ProfileCipherInputStream;
import org.session.libsignal.service.api.messages.SignalServiceAttachment.ProgressListener;
import org.session.libsignal.service.api.messages.SignalServiceAttachmentPointer;
import org.session.libsignal.service.api.messages.SignalServiceDataMessage;
import org.session.libsignal.service.loki.utilities.DownloadUtilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The primary interface for receiving Signal Service messages.
 *
 * @author Moxie Marlinspike
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class SignalServiceMessageReceiver {
  /**
   * Retrieves a SignalServiceAttachment.
   *
   * @param pointer The {@link SignalServiceAttachmentPointer}
   *                received in a {@link SignalServiceDataMessage}.
   * @param destination The download destination for this attachment.
   *
   * @return An InputStream that streams the plaintext attachment contents.
   * @throws IOException
   * @throws InvalidMessageException
   */
  public InputStream retrieveAttachment(SignalServiceAttachmentPointer pointer, File destination, int maxSizeBytes)
      throws IOException, InvalidMessageException
  {
    return retrieveAttachment(pointer, destination, maxSizeBytes, null);
  }

  public InputStream retrieveProfileAvatar(String path, File destination, byte[] profileKey, int maxSizeBytes)
    throws IOException
  {
    DownloadUtilities.downloadFile(destination, path, maxSizeBytes, null);
    return new ProfileCipherInputStream(new FileInputStream(destination), profileKey);
  }

  /**
   * Retrieves a SignalServiceAttachment.
   *
   * @param pointer The {@link SignalServiceAttachmentPointer}
   *                received in a {@link SignalServiceDataMessage}.
   * @param destination The download destination for this attachment.
   * @param listener An optional listener (may be null) to receive callbacks on download progress.
   *
   * @return An InputStream that streams the plaintext attachment contents.
   * @throws IOException
   * @throws InvalidMessageException
   */
  public InputStream retrieveAttachment(SignalServiceAttachmentPointer pointer, File destination, int maxSizeBytes, ProgressListener listener)
      throws IOException, InvalidMessageException
  {
    // Loki - Fetch attachment
    if (pointer.getUrl().isEmpty()) throw new InvalidMessageException("Missing attachment URL.");
    DownloadUtilities.downloadFile(destination, pointer.getUrl(), maxSizeBytes, listener);

    // Loki - Assume we're retrieving an attachment for an open group server if the digest is not set
    if (!pointer.getDigest().isPresent()) { return new FileInputStream(destination); }

    return AttachmentCipherInputStream.createForAttachment(destination, pointer.getSize().or(0), pointer.getKey(), pointer.getDigest().get());
  }
}
