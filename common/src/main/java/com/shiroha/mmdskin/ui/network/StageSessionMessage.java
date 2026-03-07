package com.shiroha.mmdskin.ui.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 多人舞台 V2 协议消息。
 *
 * 使用 Base64(JSON) 避免旧协议的分隔符冲突和弱解析问题。
 */
public final class StageSessionMessage {
    private static final Logger logger = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();
    private static final String PREFIX = "V2:";

    public enum Type {
        INVITE,
        INVITE_CANCEL,
        INVITE_REPLY,
        SESSION_SNAPSHOT,
        READY_STATE,
        MEMBER_LEAVE,
        SESSION_DISSOLVE,
        WATCH_START,
        WATCH_END,
        FRAME_SYNC
    }

    public enum Reply {
        ACCEPT,
        DECLINE,
        BUSY
    }

    public static final class MemberSnapshot {
        public String uuid;
        public String name;
        public String state;
        public boolean useHostCamera;

        public MemberSnapshot() {
        }

        public MemberSnapshot(String uuid, String name, String state, boolean useHostCamera) {
            this.uuid = uuid;
            this.name = name;
            this.state = state;
            this.useHostCamera = useHostCamera;
        }
    }

    public int version = 2;
    public Type type;
    public String sessionId;
    public String targetUUID;
    public Reply reply;
    public Boolean ready;
    public Boolean useHostCamera;
    public Float frame;
    public Float heightOffset;
    public String stageData;
    public List<MemberSnapshot> members = Collections.emptyList();

    public StageSessionMessage() {
    }

    public StageSessionMessage(Type type) {
        this.type = type;
    }

    public static String encode(StageSessionMessage message) {
        String json = GSON.toJson(message);
        return PREFIX + ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isV2Message(String raw) {
        return raw != null && raw.startsWith(PREFIX);
    }

    public static StageSessionMessage decode(String raw) {
        if (!isV2Message(raw)) {
            return null;
        }
        try {
            byte[] jsonBytes = DECODER.decode(raw.substring(PREFIX.length()));
            StageSessionMessage message = GSON.fromJson(new String(jsonBytes, StandardCharsets.UTF_8), StageSessionMessage.class);
            if (message == null || message.version != 2 || message.type == null) {
                return null;
            }
            if (message.members == null) {
                message.members = Collections.emptyList();
            }
            return message;
        } catch (Exception e) {
            logger.warn("[多人舞台] V2 消息解析失败: {}", e.getMessage());
            return null;
        }
    }

    public UUID getSessionUUID() {
        return parseUUID(sessionId);
    }

    public UUID getTargetUUID() {
        return parseUUID(targetUUID);
    }

    private static UUID parseUUID(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
