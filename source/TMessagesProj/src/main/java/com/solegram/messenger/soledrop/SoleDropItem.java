/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

/**
 * What SoleDrop sends: one of the {@link SoleDropProtocol} item types with what the sending phone needs to
 * send it through Telegram afterwards. Only {@link #title} and {@link #detail} (a short preview) go over NFC.
 */
public final class SoleDropItem {

    public final int type;
    /** The messages to forward ({@link SoleDropProtocol#ITEM_MESSAGES}), in the order of the chat. */
    public final ArrayList<MessageObject> messages = new ArrayList<>();
    /** A user (> 0), group or channel (< 0) to share ({@link SoleDropProtocol#ITEM_PEER}). */
    public long peerId;
    /** The picture to send ({@link SoleDropProtocol#ITEM_PHOTO}). */
    @Nullable
    public Uri photo;
    /** Text or link ({@link SoleDropProtocol#ITEM_LINK}). */
    @Nullable
    public String text;
    @Nullable
    public TLRPC.StickerSet stickerSet;
    /** Where the phone is ({@link SoleDropProtocol#ITEM_LOCATION}), known once {@link #hasLocation}. */
    public double latitude, longitude;
    public float accuracy;
    public boolean hasLocation;

    private SoleDropItem(int type) {
        this.type = type;
    }

    public static SoleDropItem myAccount() {
        return new SoleDropItem(SoleDropProtocol.ITEM_MY_ACCOUNT);
    }

    public static SoleDropItem stars() {
        return new SoleDropItem(SoleDropProtocol.ITEM_STARS);
    }

    public static SoleDropItem gift() {
        return new SoleDropItem(SoleDropProtocol.ITEM_GIFT);
    }

    public static SoleDropItem location() {
        return new SoleDropItem(SoleDropProtocol.ITEM_LOCATION);
    }

    public static SoleDropItem photo(@Nullable Uri uri) {
        final SoleDropItem item = new SoleDropItem(SoleDropProtocol.ITEM_PHOTO);
        item.photo = uri;
        return item;
    }

    public static SoleDropItem link(@Nullable String text) {
        final SoleDropItem item = new SoleDropItem(SoleDropProtocol.ITEM_LINK);
        item.text = text;
        return item;
    }

    public static SoleDropItem stickers(TLRPC.StickerSet set) {
        final SoleDropItem item = new SoleDropItem(SoleDropProtocol.ITEM_STICKERS);
        item.stickerSet = set;
        return item;
    }

    /** A user, group or channel; one's own account is {@link #myAccount()} (the contact exchange). */
    public static SoleDropItem peer(int account, long dialogId) {
        if (dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return myAccount();
        }
        final SoleDropItem item = new SoleDropItem(SoleDropProtocol.ITEM_PEER);
        item.peerId = dialogId;
        return item;
    }

    /** Messages, or null when none of them may be forwarded. */
    @Nullable
    public static SoleDropItem messages(List<MessageObject> list) {
        final SoleDropItem item = new SoleDropItem(SoleDropProtocol.ITEM_MESSAGES);
        for (MessageObject m : list) {
            if (m != null && m.messageOwner != null && m.getId() > 0 && !m.messageOwner.noforwards && !(m.messageOwner instanceof TLRPC.TL_messageService)) {
                item.messages.add(m);
            }
        }
        if (item.messages.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(item.messages, (a, b) -> Integer.compare(a.getId(), b.getId()));
        return item;
    }

    public boolean isNameDrop() {
        return type == SoleDropProtocol.ITEM_MY_ACCOUNT;
    }

    /** Stars and gifts are chosen and paid in Telegram's own sheets after the tap. */
    public boolean usesTelegramSheet() {
        return type == SoleDropProtocol.ITEM_STARS || type == SoleDropProtocol.ITEM_GIFT;
    }

    /** The item can be sent as it is (a photo was picked, the location is known, there is a link...). */
    public boolean isReady() {
        switch (type) {
            case SoleDropProtocol.ITEM_PHOTO:
                return photo != null;
            case SoleDropProtocol.ITEM_LOCATION:
                return hasLocation;
            case SoleDropProtocol.ITEM_LINK:
                return !TextUtils.isEmpty(text);
            case SoleDropProtocol.ITEM_MESSAGES:
                return !messages.isEmpty();
            default:
                return true;
        }
    }

    // region Preview

    /** The first line of the preview: what it is. */
    public String title(int account) {
        final MessagesController mc = MessagesController.getInstance(account);
        switch (type) {
            case SoleDropProtocol.ITEM_MESSAGES:
                if (messages.size() == 1) {
                    final String text = messageText(messages.get(0));
                    return TextUtils.isEmpty(text) ? messageKind(messages.get(0)) : text;
                }
                return LocaleController.formatPluralString("messages", messages.size());
            case SoleDropProtocol.ITEM_MY_ACCOUNT: {
                final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
                return self != null ? UserObject.getUserName(self) : LocaleController.getString(R.string.SG_SoleDropMyProfile);
            }
            case SoleDropProtocol.ITEM_PEER:
                if (peerId > 0) {
                    final TLRPC.User user = mc.getUser(peerId);
                    return user != null ? UserObject.getUserName(user) : "";
                } else {
                    final TLRPC.Chat chat = mc.getChat(-peerId);
                    return chat != null ? chat.title : "";
                }
            case SoleDropProtocol.ITEM_STARS:
                return LocaleController.getString(R.string.SG_SoleDropStars);
            case SoleDropProtocol.ITEM_GIFT:
                return LocaleController.getString(R.string.SG_SoleDropGift);
            case SoleDropProtocol.ITEM_PHOTO:
                return LocaleController.getString(R.string.SG_SoleDropPhoto);
            case SoleDropProtocol.ITEM_LOCATION:
                return LocaleController.getString(R.string.SG_SoleDropLocation);
            case SoleDropProtocol.ITEM_LINK:
                return TextUtils.isEmpty(text) ? LocaleController.getString(R.string.SG_SoleDropLink) : shortLink(text);
            case SoleDropProtocol.ITEM_STICKERS:
                return stickerSet != null ? stickerSet.title : LocaleController.getString(R.string.SG_SoleDropStickers);
            default:
                return "";
        }
    }

    /** The second line: what kind of thing (a username, "Channel", "3 photos"...), may be empty. */
    public String detail(int account) {
        final MessagesController mc = MessagesController.getInstance(account);
        switch (type) {
            case SoleDropProtocol.ITEM_MESSAGES:
                return messages.size() == 1 ? messageKind(messages.get(0)) : "";
            case SoleDropProtocol.ITEM_MY_ACCOUNT: {
                final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
                final String username = self != null ? UserObject.getPublicUsername(self) : null;
                return username != null ? "@" + username : LocaleController.getString(R.string.SG_SoleDropContact);
            }
            case SoleDropProtocol.ITEM_PEER: {
                if (peerId > 0) {
                    final TLRPC.User user = mc.getUser(peerId);
                    final String username = user != null ? UserObject.getPublicUsername(user) : null;
                    if (username != null) {
                        return "@" + username;
                    }
                    return LocaleController.getString(user != null && user.bot ? R.string.SG_SoleDropBot : R.string.SG_SoleDropAccount);
                }
                final TLRPC.Chat chat = mc.getChat(-peerId);
                final String username = chat != null ? ChatObject.getPublicUsername(chat) : null;
                if (username != null) {
                    return "@" + username;
                }
                return LocaleController.getString(ChatObject.isChannelAndNotMegaGroup(chat) ? R.string.SG_SoleDropChannel : R.string.SG_SoleDropGroup);
            }
            case SoleDropProtocol.ITEM_STICKERS:
                return LocaleController.getString(R.string.SG_SoleDropStickers);
            default:
                return "";
        }
    }

    /** "Name sends: ..." on the receiving phone, from the offer's item type alone. */
    public static String kindName(int item) {
        switch (item) {
            case SoleDropProtocol.ITEM_MESSAGES:
                return LocaleController.getString(R.string.SG_SoleDropKindMessage);
            case SoleDropProtocol.ITEM_MY_ACCOUNT:
                return LocaleController.getString(R.string.SG_SoleDropKindContact);
            case SoleDropProtocol.ITEM_PEER:
            case SoleDropProtocol.ITEM_CONTACT:
                return LocaleController.getString(R.string.SG_SoleDropKindPeer);
            case SoleDropProtocol.ITEM_STARS:
                return LocaleController.getString(R.string.SG_SoleDropKindStars);
            case SoleDropProtocol.ITEM_GIFT:
                return LocaleController.getString(R.string.SG_SoleDropKindGift);
            case SoleDropProtocol.ITEM_PHOTO:
                return LocaleController.getString(R.string.SG_SoleDropKindPhoto);
            case SoleDropProtocol.ITEM_LOCATION:
                return LocaleController.getString(R.string.SG_SoleDropKindLocation);
            case SoleDropProtocol.ITEM_LINK:
                return LocaleController.getString(R.string.SG_SoleDropKindLink);
            case SoleDropProtocol.ITEM_STICKERS:
                return LocaleController.getString(R.string.SG_SoleDropKindStickers);
            default:
                return LocaleController.getString(R.string.SG_SoleDropKindSomething);
        }
    }

    /** The icon of an item type (tiles, the receiving card). */
    public static int icon(int item) {
        switch (item) {
            case SoleDropProtocol.ITEM_MESSAGES:
                return R.drawable.msg_forward;
            case SoleDropProtocol.ITEM_MY_ACCOUNT:
                return R.drawable.msg_openprofile;
            case SoleDropProtocol.ITEM_PEER:
            case SoleDropProtocol.ITEM_CONTACT:
                return R.drawable.msg_contact;
            case SoleDropProtocol.ITEM_STARS:
                return R.drawable.menu_stars_gift;
            case SoleDropProtocol.ITEM_GIFT:
                return R.drawable.menu_gift;
            case SoleDropProtocol.ITEM_PHOTO:
                return R.drawable.msg_gallery;
            case SoleDropProtocol.ITEM_LOCATION:
                return R.drawable.msg_location;
            case SoleDropProtocol.ITEM_LINK:
                return R.drawable.msg_link;
            case SoleDropProtocol.ITEM_STICKERS:
                return R.drawable.msg_sticker;
            default:
                return R.drawable.msg_soledrop;
        }
    }

    /** A short text of a message for the preview: never more than a line (the protocol cuts it again). */
    @Nullable
    static String messageText(MessageObject m) {
        CharSequence text = m.caption != null ? m.caption : (m.isMediaEmpty() || m.type == MessageObject.TYPE_TEXT ? m.messageText : null);
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String s = SoleDropProtocol.clean(text.toString());
        if (s.length() > 60) {
            s = s.substring(0, 59).trim() + "…";
        }
        return s;
    }

    static String messageKind(MessageObject m) {
        if (m.isRoundVideo()) {
            return LocaleController.getString(R.string.AttachRound);
        } else if (m.isVoice()) {
            return LocaleController.getString(R.string.AttachAudio);
        } else if (m.isSticker() || m.isAnimatedSticker()) {
            return LocaleController.getString(R.string.AttachSticker);
        } else if (m.isGif()) {
            return LocaleController.getString(R.string.AttachGif);
        } else if (m.isVideo()) {
            return LocaleController.getString(R.string.AttachVideo);
        } else if (m.isMusic()) {
            return LocaleController.getString(R.string.AttachMusic);
        } else if (m.isPhoto()) {
            return LocaleController.getString(R.string.AttachPhoto);
        } else if (m.getDocument() != null) {
            return LocaleController.getString(R.string.AttachDocument);
        } else if (m.messageOwner.media instanceof TLRPC.TL_messageMediaGeo || m.messageOwner.media instanceof TLRPC.TL_messageMediaVenue
            || m.messageOwner.media instanceof TLRPC.TL_messageMediaGeoLive) {
            return LocaleController.getString(R.string.AttachLocation);
        } else if (m.messageOwner.media instanceof TLRPC.TL_messageMediaContact) {
            return LocaleController.getString(R.string.AttachContact);
        } else if (m.messageOwner.media instanceof TLRPC.TL_messageMediaPoll) {
            return LocaleController.getString(R.string.Poll);
        }
        return LocaleController.getString(R.string.SG_SoleDropKindMessage);
    }

    private static String shortLink(String text) {
        String s = SoleDropProtocol.clean(text).replaceFirst("^(?i)https?://", "");
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.length() > 60 ? s.substring(0, 59) + "…" : s;
    }
    // endregion
}
