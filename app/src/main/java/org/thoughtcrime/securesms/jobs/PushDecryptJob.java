package org.thoughtcrime.securesms.jobs;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.session.libsession.messaging.jobs.Data;
import org.session.libsignal.metadata.InvalidMetadataMessageException;
import org.session.libsignal.metadata.ProtocolInvalidMessageException;
import org.session.libsignal.service.api.crypto.SignalServiceCipher;
import org.thoughtcrime.securesms.ApplicationContext;

import org.session.libsession.messaging.sending_receiving.linkpreview.LinkPreview;
import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment;
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment;
import org.session.libsession.messaging.sending_receiving.sharecontacts.Contact;
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel;
import org.session.libsession.messaging.threads.Address;
import org.session.libsession.messaging.threads.recipients.Recipient;
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier;
import org.session.libsession.utilities.GroupUtil;
import org.session.libsession.utilities.TextSecurePreferences;

import org.thoughtcrime.securesms.contactshare.ContactModelMapper;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MessagingDatabase.InsertResult;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.PushDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.linkpreview.Link;
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil;
import org.session.libsignal.utilities.logging.Log;
import org.thoughtcrime.securesms.loki.activities.HomeActivity;
import org.thoughtcrime.securesms.loki.api.SessionProtocolImpl;
import org.thoughtcrime.securesms.loki.database.LokiAPIDatabase;
import org.thoughtcrime.securesms.loki.database.LokiMessageDatabase;
import org.thoughtcrime.securesms.loki.protocol.ClosedGroupsProtocolV2;
import org.thoughtcrime.securesms.loki.protocol.MultiDeviceProtocol;
import org.thoughtcrime.securesms.loki.protocol.SessionMetaProtocol;
import org.thoughtcrime.securesms.loki.utilities.MentionManagerUtilities;
import org.thoughtcrime.securesms.mms.IncomingMediaMessage;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.sms.IncomingEncryptedMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.sms.OutgoingTextMessage;
import org.session.libsignal.libsignal.util.guava.Optional;
import org.session.libsignal.service.api.SignalServiceMessageSender;
import org.session.libsignal.service.api.messages.SignalServiceContent;
import org.session.libsignal.service.api.messages.SignalServiceDataMessage;
import org.session.libsignal.service.api.messages.SignalServiceDataMessage.Preview;
import org.session.libsignal.service.api.messages.SignalServiceEnvelope;
import org.session.libsignal.service.api.messages.SignalServiceGroup;
import org.session.libsignal.service.api.messages.SignalServiceReceiptMessage;
import org.session.libsignal.service.api.messages.SignalServiceTypingMessage;
import org.session.libsignal.service.api.messages.shared.SharedContact;
import org.session.libsignal.service.loki.utilities.mentions.MentionsManager;
import org.session.libsignal.service.loki.utilities.PublicKeyValidation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

import network.loki.messenger.R;

public class PushDecryptJob extends BaseJob implements InjectableType {

  public static final String KEY = "PushDecryptJob";

  public static final String TAG = PushDecryptJob.class.getSimpleName();

  private static final String KEY_MESSAGE_ID     = "message_id";
  private static final String KEY_SMS_MESSAGE_ID = "sms_message_id";

  private long messageId;
  private long smsMessageId;

  private MessageNotifier messageNotifier;

  @Inject SignalServiceMessageSender messageSender;

  public PushDecryptJob(Context context) {
    this(context, -1);
  }

  public PushDecryptJob(Context context, long pushMessageId) {
    this(context, pushMessageId, -1);
  }

  public PushDecryptJob(Context context, long pushMessageId, long smsMessageId) {
    this(new Job.Parameters.Builder()
                           .setQueue("__PUSH_DECRYPT_JOB__")
                           .setMaxAttempts(10)
                           .build(),
         pushMessageId,
         smsMessageId);
    setContext(context);
    this.messageNotifier = ApplicationContext.getInstance(context).messageNotifier;
  }

  private PushDecryptJob(@NonNull Job.Parameters parameters, long pushMessageId, long smsMessageId) {
    super(parameters);

    this.messageId    = pushMessageId;
    this.smsMessageId = smsMessageId;
  }

  @Override
  public @NonNull
  Data serialize() {
    return new Data.Builder().putLong(KEY_MESSAGE_ID, messageId)
                             .putLong(KEY_SMS_MESSAGE_ID, smsMessageId)
                             .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws NoSuchMessageException {
    synchronized (PushReceivedJob.RECEIVE_LOCK) {
      if (needsMigration()) {
        Log.w(TAG, "Skipping, waiting for migration...");
        postMigrationNotification();
        return;
      }

      PushDatabase          database             = DatabaseFactory.getPushDatabase(context);
      SignalServiceEnvelope envelope             = database.get(messageId);
      Optional<Long>        optionalSmsMessageId = smsMessageId > 0 ? Optional.of(smsMessageId) : Optional.absent();

      handleMessage(envelope, optionalSmsMessageId, false);
      database.delete(messageId);
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() { }

  public void processMessage(@NonNull SignalServiceEnvelope envelope, boolean isPushNotification) {
    synchronized (PushReceivedJob.RECEIVE_LOCK) {
      if (needsMigration()) {
        Log.w(TAG, "Skipping and storing envelope, waiting for migration...");
        DatabaseFactory.getPushDatabase(context).insert(envelope);
        postMigrationNotification();
        return;
      }

      handleMessage(envelope, Optional.absent(), isPushNotification);
    }
  }

  private boolean needsMigration() {
    return !IdentityKeyUtil.hasIdentityKey(context) || TextSecurePreferences.getNeedsSqlCipherMigration(context);
  }

  private void postMigrationNotification() {
    NotificationManagerCompat.from(context).notify(494949,
                                                   new NotificationCompat.Builder(context, NotificationChannels.getMessagesChannel(context))
                                                                         .setSmallIcon(R.drawable.ic_notification)
                                                                         .setPriority(NotificationCompat.PRIORITY_HIGH)
                                                                         .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                                                         .setContentTitle(context.getString(R.string.PushDecryptJob_new_locked_message))
                                                                         .setContentText(context.getString(R.string.PushDecryptJob_unlock_to_view_pending_messages))
                                                                         .setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, HomeActivity.class), 0))
                                                                         .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
                                                                         .build());

  }

  private void handleMessage(@NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId, boolean isPushNotification) {
    try {
      GroupDatabase        groupDatabase        = DatabaseFactory.getGroupDatabase(context);
      LokiAPIDatabase apiDB                     = DatabaseFactory.getLokiAPIDatabase(context);
      SignalServiceCipher cipher                = new SignalServiceCipher(new SessionProtocolImpl(context), apiDB);

      SignalServiceContent content = cipher.decrypt(envelope);

      if (shouldIgnore(content)) {
        Log.i(TAG, "Ignoring message.");
        return;
      }

      SessionMetaProtocol.handleProfileUpdateIfNeeded(context, content);

      if (content.configurationMessageProto.isPresent()) {
        MultiDeviceProtocol.handleConfigurationMessage(context, content.configurationMessageProto.get(), content.getSender(), content.getTimestamp());
      } else if (content.getDataMessage().isPresent()) {
        SignalServiceDataMessage message        = content.getDataMessage().get();
        boolean                  isMediaMessage = message.getAttachments().isPresent() || message.getQuote().isPresent() || message.getSharedContacts().isPresent() || message.getPreviews().isPresent();

        if (message.getClosedGroupControlMessage().isPresent()) {
          ClosedGroupsProtocolV2.handleMessage(context, message.getClosedGroupControlMessage().get(), message.getTimestamp(), envelope.getSource(), content.getSender());
        }
        if (message.isExpirationUpdate()) {
          handleExpirationUpdate(content, message, smsMessageId);
        } else if (isMediaMessage) {
          handleMediaMessage(content, message, smsMessageId, Optional.absent());
        } else if (message.getBody().isPresent()) {
          handleTextMessage(content, message, smsMessageId, Optional.absent());
        }

        if (message.getGroupInfo().isPresent() && groupDatabase.isUnknownGroup(GroupUtil.getEncodedId(message.getGroupInfo().get()))) {
          handleUnknownGroupMessage(content, message.getGroupInfo().get());
        }

        if (message.getProfileKey().isPresent() && message.getProfileKey().get().length == 32) {
          SessionMetaProtocol.handleProfileKeyUpdate(context, content);
        }

        if (SessionMetaProtocol.shouldSendDeliveryReceipt(message, Address.fromSerialized(content.getSender()))) {
          handleNeedsDeliveryReceipt(content, message);
        }
      } else if (content.getReceiptMessage().isPresent()) {
        SignalServiceReceiptMessage message = content.getReceiptMessage().get();

        if      (message.isReadReceipt())     handleReadReceipt(content, message);
        else if (message.isDeliveryReceipt()) handleDeliveryReceipt(content, message);
      } else if (content.getTypingMessage().isPresent()) {
        handleTypingMessage(content, content.getTypingMessage().get());
      } else {
        Log.w(TAG, "Got unrecognized message...");
      }

      resetRecipientToPush(Recipient.from(context, Address.fromSerialized(content.getSender()), false));
    } catch (ProtocolInvalidMessageException e) {
      Log.w(TAG, e);
      if (!isPushNotification) { // This can be triggered if a PN encrypted with an old session comes in after the user performed a session reset
        handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId, e);
      }
    }catch (StorageFailedException e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId, e);
    } catch (InvalidMetadataMessageException e) {
      Log.w(TAG, e);
    }
  }

  private void handleUnknownGroupMessage(@NonNull SignalServiceContent content,
                                         @NonNull SignalServiceGroup group)
  {
    if (group.getGroupType() == SignalServiceGroup.GroupType.SIGNAL) {
      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new RequestGroupInfoJob(content.getSender(), group.getGroupId()));
    }
  }

  private void handleExpirationUpdate(@NonNull SignalServiceContent content,
                                      @NonNull SignalServiceDataMessage message,
                                      @NonNull Optional<Long> smsMessageId)
      throws StorageFailedException
  {
    try {
      MmsDatabase          database     = DatabaseFactory.getMmsDatabase(context);
      Recipient            recipient    = getMessageDestination(content, message);
      IncomingMediaMessage mediaMessage = new IncomingMediaMessage(getMessageMasterDestination(content.getSender()).getAddress(),
                                                                   message.getTimestamp(), -1,
                                                                   message.getExpiresInSeconds() * 1000L, true,
                                                                   content.isNeedsReceipt(),
                                                                   Optional.absent(),
                                                                   message.getGroupInfo(),
                                                                   Optional.absent(),
                                                                   Optional.absent(),
                                                                   Optional.absent(),
                                                                   Optional.absent());

      database.insertSecureDecryptedMessageInbox(mediaMessage, -1);

      DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient, message.getExpiresInSeconds());

      if (smsMessageId.isPresent()) {
        DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
      }
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    }
  }

  public void handleMediaMessage(@NonNull SignalServiceContent content,
                                 @NonNull SignalServiceDataMessage message,
                                 @NonNull Optional<Long> smsMessageId,
                                 @NonNull Optional<Long> messageServerIDOrNull)
      throws StorageFailedException
  {
    Recipient   originalRecipient = getMessageDestination(content, message);
    Recipient   masterRecipient   = getMessageMasterDestination(content.getSender());
    String      syncTarget        = message.getSyncTarget().orNull();


    notifyTypingStoppedFromIncomingMessage(masterRecipient, content.getSender(), content.getSenderDevice());

    Optional<QuoteModel>         quote          = getValidatedQuote(message.getQuote());
    Optional<List<Contact>>      sharedContacts = getContacts(message.getSharedContacts());
    Optional<List<LinkPreview>>  linkPreviews   = getLinkPreviews(message.getPreviews(), message.getBody().or(""));

    Address masterAddress = masterRecipient.getAddress();

    if (message.isGroupMessage()) {
      masterAddress = getMessageMasterDestination(content.getSender()).getAddress();
    }

    // Handle sync message from ourselves
    if (syncTarget != null && !syncTarget.isEmpty() || TextSecurePreferences.getLocalNumber(context).equals(content.getSender())) {
      Address targetAddress = masterRecipient.getAddress();
      if (message.getGroupInfo().isPresent()) {
        targetAddress = Address.fromSerialized(GroupUtil.getEncodedId(message.getGroupInfo().get()));
      } else if (syncTarget != null && !syncTarget.isEmpty()) {
        targetAddress = Address.fromSerialized(syncTarget);
      }
      List<Attachment>             attachments    = PointerAttachment.forPointers(message.getAttachments());

      OutgoingMediaMessage mediaMessage = new OutgoingMediaMessage(masterRecipient, message.getBody().orNull(),
              attachments,
              message.getTimestamp(), -1,
              message.getExpiresInSeconds() * 1000,
              ThreadDatabase.DistributionTypes.DEFAULT, quote.orNull(),
              sharedContacts.or(Collections.emptyList()),
              linkPreviews.or(Collections.emptyList()),
              Collections.emptyList(), Collections.emptyList());

      if (DatabaseFactory.getMmsSmsDatabase(context).getMessageFor(message.getTimestamp(), targetAddress) != null) {
        Log.d("Loki","Message already exists, don't insert again");
        return;
      }

      MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
      database.beginTransaction();

      // Ignore message if it has no body and no attachments
      if (mediaMessage.getBody().isEmpty() && mediaMessage.getAttachments().isEmpty() && mediaMessage.getLinkPreviews().isEmpty()) {
        return;
      }

      Optional<InsertResult> insertResult;

      try {

        // Check if we have the thread already
        long threadID = DatabaseFactory.getLokiThreadDatabase(context).getThreadID(targetAddress.serialize());

        insertResult = database.insertSecureDecryptedMessageOutbox(mediaMessage, threadID, content.getTimestamp());

        if (insertResult.isPresent()) {
          List<DatabaseAttachment> allAttachments     = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(insertResult.get().getMessageId());
          List<DatabaseAttachment> dbAttachments      = Stream.of(allAttachments).toList();

          for (DatabaseAttachment attachment : dbAttachments) {
            ApplicationContext.getInstance(context).getJobManager().add(new AttachmentDownloadJob(insertResult.get().getMessageId(), attachment.getAttachmentId(), false));
          }

          if (smsMessageId.isPresent()) {
            DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
          }

          database.setTransactionSuccessful();
        }
      } catch (MmsException e) {
        throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
      } finally {
        database.endTransaction();
      }

    } else {
      IncomingMediaMessage mediaMessage = new IncomingMediaMessage(masterAddress, message.getTimestamp(), -1,
              message.getExpiresInSeconds() * 1000L, false, content.isNeedsReceipt(), message.getBody(), message.getGroupInfo(), message.getAttachments(),
              quote, sharedContacts, linkPreviews);
      MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
      database.beginTransaction();

      // Ignore message if it has no body and no attachments
      if (mediaMessage.getBody().isEmpty() && mediaMessage.getAttachments().isEmpty() && mediaMessage.getLinkPreviews().isEmpty()) {
        return;
      }

      Optional<InsertResult> insertResult;

      try {
        if (message.isGroupMessage()) {
          insertResult = database.insertSecureDecryptedMessageInbox(mediaMessage, -1, content.getTimestamp());
        } else {
          insertResult = database.insertSecureDecryptedMessageInbox(mediaMessage, -1);
        }

        if (insertResult.isPresent()) {
          List<DatabaseAttachment> allAttachments     = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(insertResult.get().getMessageId());
          List<DatabaseAttachment> attachments        = Stream.of(allAttachments).toList();

          for (DatabaseAttachment attachment : attachments) {
            ApplicationContext.getInstance(context).getJobManager().add(new AttachmentDownloadJob(insertResult.get().getMessageId(), attachment.getAttachmentId(), false));
          }

          if (smsMessageId.isPresent()) {
            DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
          }

          database.setTransactionSuccessful();
        }
      } catch (MmsException e) {
        throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
      } finally {
        database.endTransaction();
      }

      if (insertResult.isPresent()) {
        messageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }

      if (insertResult.isPresent()) {
        InsertResult result = insertResult.get();

        // Loki - Cache the user hex encoded public key (for mentions)
        MentionManagerUtilities.INSTANCE.populateUserPublicKeyCacheIfNeeded(result.getThreadId(), context);
        MentionsManager.shared.cache(content.getSender(), result.getThreadId());

        // Loki - Store message open group server ID if needed
        if (messageServerIDOrNull.isPresent()) {
          long messageID = result.getMessageId();
          long messageServerID = messageServerIDOrNull.get();
          LokiMessageDatabase lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(context);
          lokiMessageDatabase.setServerID(messageID, messageServerID);
        }

        // Loki - Update mapping of message ID to original thread ID
        if (result.getMessageId() > -1) {
          ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
          LokiMessageDatabase lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(context);
          long originalThreadId = threadDatabase.getOrCreateThreadIdFor(originalRecipient);
          lokiMessageDatabase.setOriginalThreadID(result.getMessageId(), originalThreadId);
        }
      }
    }
  }

  public void handleTextMessage(@NonNull SignalServiceContent content,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Optional<Long> smsMessageId,
                                @NonNull Optional<Long> messageServerIDOrNull)
      throws StorageFailedException
  {
    SmsDatabase database          = DatabaseFactory.getSmsDatabase(context);
    String      body              = message.getBody().isPresent() ? message.getBody().get() : "";
    Recipient   originalRecipient = getMessageDestination(content, message);
    Recipient   masterRecipient   = getMessageMasterDestination(content.getSender());
    String      syncTarget        = message.getSyncTarget().orNull();

    if (message.getExpiresInSeconds() != originalRecipient.getExpireMessages()) {
      handleExpirationUpdate(content, message, Optional.absent());
    }

    Long threadId = null;

    if (smsMessageId.isPresent() && !message.getGroupInfo().isPresent()) {
      threadId = database.updateBundleMessageBody(smsMessageId.get(), body).second;
    } else if (syncTarget != null && !syncTarget.isEmpty() || TextSecurePreferences.getLocalNumber(context).equals(content.getSender())) {
      Address targetAddress = masterRecipient.getAddress();
      if (message.getGroupInfo().isPresent()) {
        targetAddress = Address.fromSerialized(GroupUtil.getEncodedId(message.getGroupInfo().get()));
      } else if (syncTarget != null && !syncTarget.isEmpty()) {
        targetAddress = Address.fromSerialized(syncTarget);
      }

      if (DatabaseFactory.getMmsSmsDatabase(context).getMessageFor(message.getTimestamp(), targetAddress) != null) {
        Log.d("Loki","Message already exists, don't insert again");
        return;
      }

      OutgoingTextMessage tm = new OutgoingTextMessage(Recipient.from(context, targetAddress, false),
              body, message.getExpiresInSeconds(), -1);

      // Ignore the message if it has no body
      if (tm.getMessageBody().length() == 0) { return; }

      // Check if we have the thread already
      long threadID = DatabaseFactory.getLokiThreadDatabase(context).getThreadID(targetAddress.serialize());


      // Insert the message into the database
      Optional<InsertResult> insertResult;
      insertResult = database.insertMessageOutbox(threadID, tm, content.getTimestamp());

      if (insertResult.isPresent()) {
        threadId = insertResult.get().getThreadId();
      }

      if (smsMessageId.isPresent()) database.deleteMessage(smsMessageId.get());

      if (threadId != null) {
        messageNotifier.updateNotification(context, threadId);
      }

      if (insertResult.isPresent()) {
        InsertResult result = insertResult.get();

        // Loki - Cache the user hex encoded public key (for mentions)
        MentionManagerUtilities.INSTANCE.populateUserPublicKeyCacheIfNeeded(result.getThreadId(), context);
        MentionsManager.shared.cache(content.getSender(), result.getThreadId());
      }

    } else {
      notifyTypingStoppedFromIncomingMessage(masterRecipient, content.getSender(), content.getSenderDevice());

      Address masterAddress = masterRecipient.getAddress();

      IncomingTextMessage tm = new IncomingTextMessage(masterAddress,
                                                       content.getSenderDevice(),
                                                       message.getTimestamp(), body,
                                                       message.getGroupInfo(),
                                                       message.getExpiresInSeconds() * 1000L,
                                                       content.isNeedsReceipt());

      IncomingEncryptedMessage textMessage = new IncomingEncryptedMessage(tm, body);

      // Ignore the message if it has no body
      if (textMessage.getMessageBody().length() == 0) { return; }

      // Insert the message into the database
      Optional<InsertResult> insertResult;
      if (message.isGroupMessage()) {
        insertResult = database.insertMessageInbox(textMessage, content.getTimestamp());
      } else {
        insertResult = database.insertMessageInbox(textMessage);
      }

      if (insertResult.isPresent()) {
        threadId = insertResult.get().getThreadId();
      }

      if (smsMessageId.isPresent()) database.deleteMessage(smsMessageId.get());

      if (threadId != null) {
        messageNotifier.updateNotification(context, threadId);
      }

      if (insertResult.isPresent()) {
        InsertResult result = insertResult.get();

        // Loki - Cache the user hex encoded public key (for mentions)
        MentionManagerUtilities.INSTANCE.populateUserPublicKeyCacheIfNeeded(result.getThreadId(), context);
        MentionsManager.shared.cache(content.getSender(), result.getThreadId());

        // Loki - Store message open group server ID if needed
        if (messageServerIDOrNull.isPresent()) {
          long messageID = result.getMessageId();
          long messageServerID = messageServerIDOrNull.get();
          LokiMessageDatabase lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(context);
          lokiMessageDatabase.setServerID(messageID, messageServerID);
        }

        // Loki - Update mapping of message ID to original thread ID
        if (result.getMessageId() > -1) {
          ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
          LokiMessageDatabase lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(context);
          long originalThreadId = threadDatabase.getOrCreateThreadIdFor(originalRecipient);
          lokiMessageDatabase.setOriginalThreadID(result.getMessageId(), originalThreadId);
        }
      }
    }
  }

  private void handleCorruptMessage(@NonNull String sender, int senderDevice, long timestamp,
                                    @NonNull Optional<Long> smsMessageId, @NonNull Throwable e)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);
    if (!SessionMetaProtocol.shouldIgnoreDecryptionException(context, timestamp)) {
      if (!smsMessageId.isPresent()) {
        Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

        if (insertResult.isPresent()) {
          smsDatabase.markAsDecryptFailed(insertResult.get().getMessageId());
          messageNotifier.updateNotification(context, insertResult.get().getThreadId());
        }
      } else {
        smsDatabase.markAsDecryptFailed(smsMessageId.get());
      }
    }
  }

  private void handleNeedsDeliveryReceipt(@NonNull SignalServiceContent content,
                                          @NonNull SignalServiceDataMessage message)
  {
    ApplicationContext.getInstance(context)
                      .getJobManager()
                      .add(new SendDeliveryReceiptJob(Address.fromSerialized(content.getSender()), message.getTimestamp()));
  }

  @SuppressLint("DefaultLocale")
  private void handleDeliveryReceipt(@NonNull SignalServiceContent content,
                                     @NonNull SignalServiceReceiptMessage message)
  {
    // Redirect message to master device conversation
    Address masterAddress = Address.fromSerialized(content.getSender());

    if (masterAddress.isContact()) {
      Recipient masterRecipient = getMessageMasterDestination(content.getSender());
      masterAddress = masterRecipient.getAddress();
    }

    for (long timestamp : message.getTimestamps()) {
      Log.i(TAG, String.format("Received encrypted delivery receipt: (XXXXX, %d)", timestamp));
      DatabaseFactory.getMmsSmsDatabase(context)
                     .incrementDeliveryReceiptCount(new SyncMessageId(masterAddress, timestamp), System.currentTimeMillis());
    }
  }

  @SuppressLint("DefaultLocale")
  private void handleReadReceipt(@NonNull SignalServiceContent content,
                                 @NonNull SignalServiceReceiptMessage message)
  {
    if (TextSecurePreferences.isReadReceiptsEnabled(context)) {

      // Redirect message to master device conversation
      Address masterAddress = Address.fromSerialized(content.getSender());

      if (masterAddress.isContact()) {
        Recipient masterRecipient = getMessageMasterDestination(content.getSender());
        masterAddress = masterRecipient.getAddress();
      }

      for (long timestamp : message.getTimestamps()) {
        Log.i(TAG, String.format("Received encrypted read receipt: (XXXXX, %d)", timestamp));

        DatabaseFactory.getMmsSmsDatabase(context)
                       .incrementReadReceiptCount(new SyncMessageId(masterAddress, timestamp), content.getTimestamp());
      }
    }
  }

  private void handleTypingMessage(@NonNull SignalServiceContent content,
                                   @NonNull SignalServiceTypingMessage typingMessage)
  {
    if (!TextSecurePreferences.isTypingIndicatorsEnabled(context)) {
      return;
    }
    long threadId;

    Recipient author = getMessageMasterDestination(content.getSender());
    threadId = DatabaseFactory.getThreadDatabase(context).getOrCreateThreadIdFor(author);

    if (threadId <= 0) {
      Log.w(TAG, "Couldn't find a matching thread for a typing message.");
      return;
    }

    if (typingMessage.isTypingStarted()) {
      Log.d(TAG, "Typing started on thread " + threadId);
      ApplicationContext.getInstance(context).getTypingStatusRepository().didReceiveTypingStartedMessage(context,threadId, author.getAddress(), content.getSenderDevice());
    } else {
      Log.d(TAG, "Typing stopped on thread " + threadId);
      ApplicationContext.getInstance(context).getTypingStatusRepository().didReceiveTypingStoppedMessage(context, threadId, author.getAddress(), content.getSenderDevice(), false);
    }
  }

  private Optional<QuoteModel> getValidatedQuote(Optional<SignalServiceDataMessage.Quote> quote) {
    if (!quote.isPresent()) return Optional.absent();

    if (quote.get().getId() <= 0) {
      Log.w(TAG, "Received quote without an ID! Ignoring...");
      return Optional.absent();
    }

    if (quote.get().getAuthor() == null) {
      Log.w(TAG, "Received quote without an author! Ignoring...");
      return Optional.absent();
    }

    Address       author  = Address.fromSerialized(quote.get().getAuthor().getNumber());
    MessageRecord message = DatabaseFactory.getMmsSmsDatabase(context).getMessageFor(quote.get().getId(), author);

    if (message != null) {
      Log.i(TAG, "Found matching message record...");

      List<Attachment> attachments = new LinkedList<>();

      if (message.isMms()) {
        MmsMessageRecord mmsMessage = (MmsMessageRecord) message;
        attachments = mmsMessage.getSlideDeck().asAttachments();
        if (attachments.isEmpty()) {
          attachments.addAll(Stream.of(mmsMessage.getLinkPreviews())
                                   .filter(lp -> lp.getThumbnail().isPresent())
                                   .map(lp -> lp.getThumbnail().get())
                                   .toList());
        }
      }

      return Optional.of(new QuoteModel(quote.get().getId(), author, message.getBody(), false, attachments));
    }

    Log.w(TAG, "Didn't find matching message record...");
    return Optional.of(new QuoteModel(quote.get().getId(),
                                      author,
                                      quote.get().getText(),
                                      true,
                                      PointerAttachment.forPointersOfDataMessage(quote.get().getAttachments())));
  }

  private Optional<List<Contact>> getContacts(Optional<List<SharedContact>> sharedContacts) {
    if (!sharedContacts.isPresent()) return Optional.absent();

    List<Contact> contacts = new ArrayList<>(sharedContacts.get().size());

    for (SharedContact sharedContact : sharedContacts.get()) {
      contacts.add(ContactModelMapper.remoteToLocal(sharedContact));
    }

    return Optional.of(contacts);
  }

  private Optional<List<LinkPreview>> getLinkPreviews(Optional<List<Preview>> previews, @NonNull String message) {
    if (!previews.isPresent()) return Optional.absent();

    List<LinkPreview> linkPreviews = new ArrayList<>(previews.get().size());

    for (Preview preview : previews.get()) {
      Optional<Attachment> thumbnail     = PointerAttachment.forPointer(preview.getImage());
      Optional<String>     url           = Optional.fromNullable(preview.getUrl());
      Optional<String>     title         = Optional.fromNullable(preview.getTitle());
      boolean              hasContent    = !TextUtils.isEmpty(title.or("")) || thumbnail.isPresent();
      boolean              presentInBody = url.isPresent() && Stream.of(LinkPreviewUtil.findWhitelistedUrls(message)).map(Link::getUrl).collect(Collectors.toSet()).contains(url.get());
      boolean              validDomain   = url.isPresent() && LinkPreviewUtil.isValidLinkUrl(url.get());

      if (hasContent && presentInBody && validDomain) {
        LinkPreview linkPreview = new LinkPreview(url.get(), title.or(""), thumbnail);
        linkPreviews.add(linkPreview);
      } else {
        Log.w(TAG, String.format("Discarding an invalid link preview. hasContent: %b presentInBody: %b validDomain: %b", hasContent, presentInBody, validDomain));
      }
    }

    return Optional.of(linkPreviews);
  }

  private Optional<InsertResult> insertPlaceholder(@NonNull String sender, int senderDevice, long timestamp) {
    Recipient           masterRecipient = getMessageMasterDestination(sender);
    SmsDatabase         database        = DatabaseFactory.getSmsDatabase(context);
    IncomingTextMessage textMessage     = new IncomingTextMessage(masterRecipient.getAddress(),
                                                                 senderDevice, timestamp, "",
                                                                 Optional.absent(), 0, false);

    textMessage = new IncomingEncryptedMessage(textMessage, "");
    return database.insertMessageInbox(textMessage);
  }

  private Recipient getMessageDestination(SignalServiceContent content, SignalServiceDataMessage message) {
    if (message.getGroupInfo().isPresent()) {
      return Recipient.from(context, Address.fromExternal(context, GroupUtil.getEncodedClosedGroupID(message.getGroupInfo().get().getGroupId())), false);
    } else {
      return Recipient.from(context, Address.fromExternal(context, content.getSender()), false);
    }
  }

  private Recipient getMessageMasterDestination(String publicKey) {
    if (!PublicKeyValidation.isValid(publicKey)) {
      return Recipient.from(context, Address.fromSerialized(publicKey), false);
    } else {
      String userPublicKey = TextSecurePreferences.getLocalNumber(context);
      if (publicKey.equals(userPublicKey)) {
        return Recipient.from(context, Address.fromSerialized(userPublicKey), false);
      } else {
        return Recipient.from(context, Address.fromSerialized(publicKey), false);
      }
    }
  }

  private void notifyTypingStoppedFromIncomingMessage(@NonNull Recipient conversationRecipient, @NonNull String sender, int device) {
    Recipient author   = Recipient.from(context, Address.fromSerialized(sender), false);
    long      threadId = DatabaseFactory.getThreadDatabase(context).getOrCreateThreadIdFor(conversationRecipient);

    if (threadId > 0) {
      Log.d(TAG, "Typing stopped on thread " + threadId + " due to an incoming message.");
      ApplicationContext.getInstance(context).getTypingStatusRepository().didReceiveTypingStoppedMessage(context, threadId, author.getAddress(), device, true);
    }
  }

  private boolean shouldIgnore(@Nullable SignalServiceContent content) {
    if (content == null) {
      Log.w(TAG, "Got a message with null content.");
      return true;
    }

    if (SessionMetaProtocol.shouldIgnoreMessage(content.getTimestamp())) {
      Log.d("Loki", "Ignoring duplicate message.");
      return true;
    }

    if (content.getSender().equals(TextSecurePreferences.getLocalNumber(context)) &&
            DatabaseFactory.getMmsSmsDatabase(context).getMessageFor(content.getTimestamp(), content.getSender()) != null) {
      Log.d("Loki", "Skipping message from self we already have");
      return true;
    }

    Recipient sender = Recipient.from(context, Address.fromSerialized(content.getSender()), false);

    if (content.getDataMessage().isPresent()) {
      SignalServiceDataMessage message      = content.getDataMessage().get();
      Recipient                conversation = getMessageDestination(content, message);

      if (conversation.isGroupRecipient() && conversation.isBlocked()) {
        return true;
      } else if (conversation.isGroupRecipient()) {
        GroupDatabase    groupDatabase = DatabaseFactory.getGroupDatabase(context);
        Optional<String> groupId       = message.getGroupInfo().isPresent() ? Optional.of(GroupUtil.getEncodedId(message.getGroupInfo().get()))
                                                                            : Optional.absent();

        if (groupId.isPresent() && groupDatabase.isUnknownGroup(groupId.get())) {
          return false;
        }

        boolean isTextMessage    = message.getBody().isPresent();
        boolean isMediaMessage   = message.getAttachments().isPresent() || message.getQuote().isPresent() || message.getSharedContacts().isPresent();
        boolean isExpireMessage  = message.isExpirationUpdate();
        boolean isContentMessage = !message.isGroupUpdate() && (isTextMessage || isMediaMessage || isExpireMessage);
        boolean isGroupActive    = groupId.isPresent() && groupDatabase.isActive(groupId.get());
        boolean isLeaveMessage   = message.getGroupInfo().isPresent() && message.getGroupInfo().get().getType() == SignalServiceGroup.Type.QUIT;

        return (isContentMessage && !isGroupActive) || (sender.isBlocked() && !isLeaveMessage);
      } else {
        return sender.isBlocked();
      }
    }

    return false;
  }

  private void resetRecipientToPush(@NonNull Recipient recipient) {
    if (recipient.isForceSmsSelection()) {
      DatabaseFactory.getRecipientDatabase(context).setForceSmsSelection(recipient, false);
    }
  }

  @SuppressWarnings("WeakerAccess")
  private static class StorageFailedException extends Exception {
    private final String sender;
    private final int senderDevice;

    private StorageFailedException(Exception e, String sender, int senderDevice) {
      super(e);
      this.sender       = sender;
      this.senderDevice = senderDevice;
    }

    public String getSender() {
      return sender;
    }

    public int getSenderDevice() {
      return senderDevice;
    }
  }

  public static final class Factory implements Job.Factory<PushDecryptJob> {
    @Override
    public @NonNull PushDecryptJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new PushDecryptJob(parameters, data.getLong(KEY_MESSAGE_ID), data.getLong(KEY_SMS_MESSAGE_ID));
    }
  }
}
