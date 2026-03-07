package com.shiroha.mmdskin.ui.network;

import com.shiroha.mmdskin.ui.stage.StageInviteManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 舞台模式网络通信。
 */
public class StageNetworkHandler {
    private static final Logger logger = LogManager.getLogger();

    private static Consumer<String> stageStartSender;
    private static Runnable stageEndSender;
    private static Consumer<String> stageMultiSender;

    public static void setStageStartSender(Consumer<String> sender) {
        stageStartSender = sender;
    }

    public static void setStageEndSender(Runnable sender) {
        stageEndSender = sender;
    }

    public static void setStageMultiSender(Consumer<String> sender) {
        stageMultiSender = sender;
    }

    public static void sendStageStart(String stageData) {
        if (stageStartSender != null) {
            try {
                stageStartSender.accept(stageData);
            } catch (Exception e) {
                logger.error("广播舞台开始失败", e);
            }
        }
    }

    public static void sendStageEnd() {
        if (stageEndSender != null) {
            try {
                stageEndSender.run();
            } catch (Exception e) {
                logger.error("广播舞台结束失败", e);
            }
        }
    }

    public static void sendStageInvite(UUID targetUUID, UUID sessionId) {
        StageSessionMessage message = directed(StageSessionMessage.Type.INVITE, targetUUID, sessionId);
        sendMultiMessage(message);
        sendLegacyMulti("INVITE|" + targetUUID);
    }

    public static void sendInviteCancel(UUID targetUUID, UUID sessionId) {
        StageSessionMessage message = directed(StageSessionMessage.Type.INVITE_CANCEL, targetUUID, sessionId);
        sendMultiMessage(message);
        sendLegacyMulti("INVITE_CANCEL|" + targetUUID);
    }

    public static void sendInviteResponse(UUID hostUUID, UUID sessionId, StageSessionMessage.Reply reply) {
        StageSessionMessage message = directed(StageSessionMessage.Type.INVITE_REPLY, hostUUID, sessionId);
        message.reply = reply;
        sendMultiMessage(message);
        if (reply == StageSessionMessage.Reply.ACCEPT) {
            sendLegacyMulti("ACCEPT|" + hostUUID);
        } else if (reply == StageSessionMessage.Reply.DECLINE || reply == StageSessionMessage.Reply.BUSY) {
            sendLegacyMulti("DECLINE|" + hostUUID);
        }
    }

    public static void sendSessionSnapshot(UUID targetUUID, UUID sessionId,
                                           List<StageSessionMessage.MemberSnapshot> members) {
        StageSessionMessage message = directed(StageSessionMessage.Type.SESSION_SNAPSHOT, targetUUID, sessionId);
        message.members = members;
        sendMultiMessage(message);
    }

    public static void sendReady(UUID hostUUID, UUID sessionId, boolean ready, boolean useHostCamera) {
        StageSessionMessage message = directed(StageSessionMessage.Type.READY_STATE, hostUUID, sessionId);
        message.ready = ready;
        message.useHostCamera = useHostCamera;
        sendMultiMessage(message);
        if (ready) {
            sendLegacyMulti("READY|" + hostUUID + "|" + (useHostCamera ? "1" : "0"));
        }
    }

    public static void sendLeave(UUID hostUUID, UUID sessionId) {
        StageSessionMessage message = directed(StageSessionMessage.Type.MEMBER_LEAVE, hostUUID, sessionId);
        sendMultiMessage(message);
        sendLegacyMulti("LEAVE|" + hostUUID);
    }

    public static void sendSessionDissolve(UUID targetUUID, UUID sessionId) {
        StageSessionMessage message = directed(StageSessionMessage.Type.SESSION_DISSOLVE, targetUUID, sessionId);
        sendMultiMessage(message);
        sendLegacyMulti("SESSION_DISSOLVE|" + targetUUID);
    }

    public static void sendStageWatch(UUID targetUUID, UUID sessionId, String stageData,
                                      float heightOffset, float startFrame) {
        StageSessionMessage message = directed(StageSessionMessage.Type.WATCH_START, targetUUID, sessionId);
        message.stageData = stageData;
        message.heightOffset = heightOffset;
        message.frame = startFrame;
        sendMultiMessage(message);
        sendLegacyMulti("WATCH_START|" + targetUUID + "|" + stageData
                + "|HEIGHT:" + heightOffset + "|FRAME:" + startFrame);
    }

    public static void sendStageWatchEnd(UUID targetUUID, UUID sessionId) {
        StageSessionMessage message = directed(StageSessionMessage.Type.WATCH_END, targetUUID, sessionId);
        sendMultiMessage(message);
        sendLegacyMulti("WATCH_END|" + targetUUID);
    }

    public static void sendFrameSync(UUID sessionId, float currentFrame) {
        StageSessionMessage message = new StageSessionMessage(StageSessionMessage.Type.FRAME_SYNC);
        if (sessionId != null) {
            message.sessionId = sessionId.toString();
        }
        message.frame = currentFrame;
        sendMultiMessage(message);
        sendLegacyMulti("SYNC_FRAME|" + currentFrame);
    }

    public static void sendStageWatch(UUID targetUUID, String stageData) {
        sendStageWatch(targetUUID, StageInviteManager.getInstance().getSessionId(), stageData, 0.0f, 0.0f);
    }

    public static void sendStageWatchEnd(UUID targetUUID) {
        sendStageWatchEnd(targetUUID, StageInviteManager.getInstance().getSessionId());
    }

    public static void sendLeave(UUID hostUUID) {
        sendLeave(hostUUID, StageInviteManager.getInstance().getSessionId());
    }

    public static void sendReady(UUID hostUUID, boolean useHostCamera) {
        sendReady(hostUUID, StageInviteManager.getInstance().getSessionId(), true, useHostCamera);
    }

    public static void sendFrameSync(float currentFrame) {
        sendFrameSync(StageInviteManager.getInstance().getSessionId(), currentFrame);
    }

    private static StageSessionMessage directed(StageSessionMessage.Type type, UUID targetUUID, UUID sessionId) {
        StageSessionMessage message = new StageSessionMessage(type);
        if (targetUUID != null) {
            message.targetUUID = targetUUID.toString();
        }
        if (sessionId != null) {
            message.sessionId = sessionId.toString();
        }
        return message;
    }

    private static void sendMultiMessage(StageSessionMessage message) {
        sendMulti(StageSessionMessage.encode(message));
    }

    private static void sendMulti(String data) {
        if (stageMultiSender == null) {
            logger.warn("[多人舞台] stageMultiSender 未注册");
            return;
        }
        try {
            stageMultiSender.accept(data);
        } catch (Exception e) {
            logger.error("多人舞台消息发送失败", e);
        }
    }

    private static void sendLegacyMulti(String data) {
        sendMulti(data);
    }
}
