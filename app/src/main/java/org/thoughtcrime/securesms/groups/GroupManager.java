package org.thoughtcrime.securesms.groups;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;

import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.messaging.sending_receiving.attachments.UriAttachment;
import org.session.libsession.messaging.threads.Address;
import org.session.libsession.messaging.threads.recipients.Recipient;
import org.session.libsession.utilities.GroupUtil;
import org.session.libsession.utilities.MediaTypes;
import org.session.libsession.utilities.TextSecurePreferences;

import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.session.libsignal.service.internal.push.SignalServiceProtos.GroupContext;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class GroupManager {

  public static long getOpenGroupThreadID(String id, @NonNull  Context context) {
    final String groupID = GroupUtil.getEncodedOpenGroupID(id.getBytes());
    return getThreadIDFromGroupID(groupID, context);
  }

  public static long getThreadIDFromGroupID(String groupID, @NonNull  Context context) {
    final Recipient groupRecipient = Recipient.from(context, Address.fromSerialized(groupID), false);
    return DatabaseFactory.getThreadDatabase(context).getThreadIdIfExistsFor(groupRecipient);
  }

  public static @NonNull GroupActionResult createOpenGroup(@NonNull  String  id,
                                                           @NonNull  Context context,
                                                           @Nullable Bitmap  avatar,
                                                           @Nullable String  name)
  {
    final String groupID = GroupUtil.getEncodedOpenGroupID(id.getBytes());
    return createLokiGroup(groupID, context, avatar, name);
  }

  private static @NonNull GroupActionResult createLokiGroup(@NonNull  String  groupId,
                                                            @NonNull  Context context,
                                                            @Nullable Bitmap  avatar,
                                                            @Nullable String  name)
  {
    final byte[]        avatarBytes     = BitmapUtil.toByteArray(avatar);
    final GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    final Recipient     groupRecipient  = Recipient.from(context, Address.fromSerialized(groupId), false);
    final Set<Address>  memberAddresses = new HashSet<>();

    memberAddresses.add(Address.fromSerialized(Objects.requireNonNull(TextSecurePreferences.getLocalNumber(context))));
    groupDatabase.create(groupId, name, new LinkedList<>(memberAddresses), null, null, new LinkedList<>(), System.currentTimeMillis());

    groupDatabase.updateProfilePicture(groupId, avatarBytes);

    long threadID = DatabaseFactory.getThreadDatabase(context).getOrCreateThreadIdFor(
            groupRecipient, ThreadDatabase.DistributionTypes.CONVERSATION);
    return new GroupActionResult(groupRecipient, threadID);
  }

  public static boolean deleteGroup(@NonNull String  groupId,
                                    @NonNull Context context)
  {
    final GroupDatabase  groupDatabase  = DatabaseFactory.getGroupDatabase(context);
    final ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
    final Recipient      groupRecipient = Recipient.from(context, Address.fromSerialized(groupId), false);

    if (!groupDatabase.getGroup(groupId).isPresent()) {
      return false;
    }

    long threadId = threadDatabase.getThreadIdIfExistsFor(groupRecipient);
    if (threadId != -1L) {
      threadDatabase.deleteConversation(threadId);
    }

    return groupDatabase.delete(groupId);
  }

  public static GroupActionResult updateGroup(@NonNull  Context        context,
                                              @NonNull  String         groupId,
                                              @NonNull  Set<Recipient> members,
                                              @Nullable Bitmap         avatar,
                                              @Nullable String         name,
                                              @NonNull  Set<Recipient> admins)
  {
    final GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    final Set<Address>  memberAddresses = getMemberAddresses(members);
    final Set<Address>  adminAddresses  = getMemberAddresses(admins);
    final byte[]        avatarBytes     = BitmapUtil.toByteArray(avatar);

    memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
    groupDatabase.updateMembers(groupId, new LinkedList<>(memberAddresses));
    groupDatabase.updateAdmins(groupId, new LinkedList<>(adminAddresses));
    groupDatabase.updateTitle(groupId, name);
    groupDatabase.updateProfilePicture(groupId, avatarBytes);

    if (!GroupUtil.isMmsGroup(groupId)) {
      return sendGroupUpdate(context, groupId, memberAddresses, name, avatarBytes, adminAddresses);
    } else {
      Recipient groupRecipient = Recipient.from(context, Address.fromSerialized(groupId), true);
      long      threadId       = DatabaseFactory.getThreadDatabase(context).getOrCreateThreadIdFor(groupRecipient);
      return new GroupActionResult(groupRecipient, threadId);
    }
  }

  private static GroupActionResult sendGroupUpdate(@NonNull  Context      context,
                                                   @NonNull  String       groupId,
                                                   @NonNull  Set<Address> members,
                                                   @Nullable String       groupName,
                                                   @Nullable byte[]       avatar,
                                                   @NonNull  Set<Address> admins)
  {
    Attachment avatarAttachment = null;
    Address    groupAddress     = Address.fromSerialized(groupId);
    Recipient  groupRecipient   = Recipient.from(context, groupAddress, false);

    List<String> numbers = new LinkedList<>();
    for (Address member : members) {
      numbers.add(member.serialize());
    }

    List<String> adminNumbers = new LinkedList<>();
    for (Address admin : admins) {
      adminNumbers.add(admin.serialize());
    }

    GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
                                                           .setId(ByteString.copyFrom(GroupUtil.getDecodedGroupIDAsData(groupId)))
                                                           .setType(GroupContext.Type.UPDATE)
                                                           .addAllMembers(numbers)
                                                           .addAllAdmins(adminNumbers);
    if (groupName != null) groupContextBuilder.setName(groupName);
    GroupContext groupContext = groupContextBuilder.build();

    if (avatar != null) {
      Uri avatarUri = BlobProvider.getInstance().forData(avatar).createForSingleUseInMemory();
      avatarAttachment = new UriAttachment(avatarUri, MediaTypes.IMAGE_PNG, AttachmentDatabase.TRANSFER_PROGRESS_DONE, avatar.length, null, false, false, null);
    }

    OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, avatarAttachment, 0, null, Collections.emptyList(), Collections.emptyList());
    long                      threadId        = MessageSender.send(context, outgoingMessage, -1, false, null);

    return new GroupActionResult(groupRecipient, threadId);
  }

  private static Set<Address> getMemberAddresses(Collection<Recipient> recipients) {
    final Set<Address> results = new HashSet<>();
    for (Recipient recipient : recipients) {
      results.add(recipient.getAddress());
    }

    return results;
  }

  public static class GroupActionResult {
    private Recipient groupRecipient;
    private long      threadId;

    public GroupActionResult(Recipient groupRecipient, long threadId) {
      this.groupRecipient = groupRecipient;
      this.threadId       = threadId;
    }

    public Recipient getGroupRecipient() {
      return groupRecipient;
    }

    public long getThreadId() {
      return threadId;
    }
  }
}
