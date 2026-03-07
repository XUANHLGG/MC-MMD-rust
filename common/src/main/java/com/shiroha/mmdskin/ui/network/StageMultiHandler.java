package com.shiroha.mmdskin.ui.network;

import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.config.PathConstants;
import com.shiroha.mmdskin.config.StageConfig;
import com.shiroha.mmdskin.renderer.camera.MMDCameraController;
import com.shiroha.mmdskin.renderer.camera.StageAudioPlayer;
import com.shiroha.mmdskin.renderer.render.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.renderer.render.StageAnimSyncHelper;
import com.shiroha.mmdskin.ui.stage.StageInviteManager;
import com.shiroha.mmdskin.ui.stage.StageSelectScreen;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.UUID;

/**
 * 多人舞台消息客户端处理（opCode 11）。
 */
public final class StageMultiHandler {
    private static final Logger logger = LogManager.getLogger();

    private StageMultiHandler() {
    }

    public static void handle(UUID senderUUID, String data) {
        if (data == null || data.isEmpty()) {
            return;
        }

        StageSessionMessage message = StageSessionMessage.decode(data);
        if (message == null) {
            handleLegacy(senderUUID, data);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        UUID selfUUID = mc.player.getUUID();
        UUID targetUUID = message.getTargetUUID();
        if (targetUUID != null && !selfUUID.equals(targetUUID)) {
            return;
        }

        StageInviteManager manager = StageInviteManager.getInstance();
        UUID sessionId = message.getSessionUUID();

        switch (message.type) {
            case INVITE -> manager.onInviteReceived(senderUUID, sessionId);
            case INVITE_CANCEL -> manager.onInviteCancelled(senderUUID, sessionId);
            case INVITE_REPLY -> manager.onInviteReply(senderUUID, sessionId, message.reply);
            case READY_STATE -> manager.onMemberReady(
                    senderUUID,
                    sessionId,
                    Boolean.TRUE.equals(message.ready),
                    Boolean.TRUE.equals(message.useHostCamera)
            );
            case MEMBER_LEAVE -> manager.onMemberLeft(senderUUID, sessionId);
            case SESSION_SNAPSHOT -> manager.onSessionSnapshot(senderUUID, sessionId, message.members);
            case SESSION_DISSOLVE -> handleSessionDissolve(senderUUID, sessionId, mc, manager);
            case WATCH_START -> handleWatchStart(senderUUID, sessionId, message, mc, manager);
            case WATCH_END -> handleWatchEnd(senderUUID, sessionId, manager);
            case FRAME_SYNC -> handleFrameSync(senderUUID, sessionId, message.frame, manager);
        }
    }

    private static void handleSessionDissolve(UUID hostUUID, UUID sessionId,
                                              Minecraft mc, StageInviteManager manager) {
        boolean affectsCurrentSession = manager.matchesCurrentSession(hostUUID, sessionId);
        manager.onSessionDissolved(hostUUID, sessionId);
        if (!affectsCurrentSession) {
            return;
        }

        MMDCameraController controller = MMDCameraController.getInstance();
        controller.setWaitingForHost(false);

        if (controller.isWatching()) {
            controller.exitWatchMode(false);
        } else if (controller.isInStageMode()) {
            controller.exitStageMode();
        }

        if (mc.screen instanceof StageSelectScreen) {
            mc.setScreen(null);
        }
    }

    private static void handleFrameSync(UUID hostUUID, UUID sessionId, Float frame,
                                        StageInviteManager manager) {
        if (frame == null || !manager.isWatchingStage()) {
            return;
        }

        UUID watchingHost = manager.getWatchingHostUUID();
        if (watchingHost == null || !watchingHost.equals(hostUUID)) {
            return;
        }
        if (sessionId != null && manager.getSessionId() != null && !sessionId.equals(manager.getSessionId())) {
            return;
        }

        float hostFrame = frame;
        MMDCameraController.getInstance().onFrameSync(hostFrame);
        StageAnimSyncHelper.syncAllRemoteStageFrame(hostFrame);
        StageAnimSyncHelper.syncLocalStageFrame(hostFrame);
        StageAudioPlayer.syncRemoteAudioPosition(hostUUID, hostFrame / 30.0f);
    }

    private static void handleWatchEnd(UUID hostUUID, UUID sessionId, StageInviteManager manager) {
        boolean affectsCurrentSession = manager.matchesCurrentSession(hostUUID, sessionId);
        if (!affectsCurrentSession) {
            return;
        }

        manager.onWatchStageEnd(hostUUID);

        MMDCameraController controller = MMDCameraController.getInstance();
        if (controller.isWatching()) {
            controller.exitWatchMode(false);
        } else if (controller.isInStageMode()) {
            controller.exitStageMode();
        }
    }

    private static void handleWatchStart(UUID hostUUID, UUID sessionId, StageSessionMessage message,
                                         Minecraft mc, StageInviteManager manager) {
        if (sessionId == null) {
            if (manager.getSessionId() != null) {
                return;
            }
        } else if (manager.getSessionId() == null || !sessionId.equals(manager.getSessionId())) {
            return;
        }
        if (manager.getWatchingHostUUID() != null && !manager.getWatchingHostUUID().equals(hostUUID)) {
            return;
        }
        if (message.stageData == null || message.stageData.isEmpty()) {
            return;
        }
        if (manager.isWatchingStage()
                && hostUUID.equals(manager.getWatchingHostUUID())
                && message.stageData.equals(manager.getWatchingStageData())) {
            return;
        }

        MMDCameraController controller = MMDCameraController.getInstance();
        if (controller.isWaitingForHost() && mc.screen instanceof StageSelectScreen screen) {
            screen.markStartedByHost();
            mc.setScreen(null);
        }

        if (!controller.isInStageMode()) {
            controller.enterStageMode();
        }

        manager.onWatchStageStart(hostUUID, message.stageData);
        controller.setWaitingForHost(false);

        float hostHeightOffset = message.heightOffset != null ? message.heightOffset : 0.0f;
        float startFrame = message.frame != null ? message.frame : 0.0f;
        float effectiveHeight = manager.isUseHostCamera()
                ? hostHeightOffset
                : StageConfig.getInstance().cameraHeightOffset;

        boolean started = loadAndStartAsGuest(
                message.stageData,
                controller,
                mc,
                effectiveHeight,
                manager.isUseHostCamera()
        );
        if (!started) {
            manager.stopWatchingStageOnly();
            controller.setWaitingForHost(true);
            mc.setScreen(new StageSelectScreen());
            return;
        }

        StageNetworkHandler.sendStageStart(message.stageData);

        if (startFrame > 0.0f) {
            StageAnimSyncHelper.syncAllRemoteStageFrame(startFrame);
            StageAnimSyncHelper.syncLocalStageFrame(startFrame);
            StageAudioPlayer.syncRemoteAudioPosition(hostUUID, startFrame / 30.0f);
        }
    }

    private static boolean loadAndStartAsGuest(String stageData, MMDCameraController controller,
                                               Minecraft mc, float heightOffset, boolean useHostCamera) {
        String[] parts = stageData.split("\\|");
        if (parts.length < 2) {
            return false;
        }

        String packName = parts[0];
        if (!isSafeName(packName)) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            if (!isSafeName(parts[i])) {
                return false;
            }
        }

        File stageDir = new File(PathConstants.getStageAnimDir(), packName);
        if (!stageDir.exists()) {
            logger.warn("[被邀请者] 本地缺少舞台包: {}", packName);
            return false;
        }

        NativeFunc nf = NativeFunc.GetInst();
        long mergedAnim = 0;
        long cameraAnim = 0;

        try {
            for (int i = 1; i < parts.length; i++) {
                String filePath = new File(stageDir, parts[i]).getAbsolutePath();
                long tempAnim = nf.LoadAnimation(0, filePath);
                if (tempAnim == 0) {
                    continue;
                }

                if (nf.HasCameraData(tempAnim) && cameraAnim == 0) {
                    cameraAnim = tempAnim;
                }

                if (nf.HasBoneData(tempAnim) || nf.HasMorphData(tempAnim)) {
                    if (mergedAnim == 0) {
                        mergedAnim = tempAnim;
                    } else {
                        nf.MergeAnimation(mergedAnim, tempAnim);
                        if (tempAnim != cameraAnim) {
                            nf.DeleteAnimation(tempAnim);
                        }
                    }
                } else if (tempAnim != cameraAnim) {
                    nf.DeleteAnimation(tempAnim);
                }
            }

            if (cameraAnim == 0) {
                File[] files = stageDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".vmd"));
                if (files != null) {
                    for (File file : files) {
                        long tempAnim = nf.LoadAnimation(0, file.getAbsolutePath());
                        if (tempAnim != 0 && nf.HasCameraData(tempAnim)) {
                            cameraAnim = tempAnim;
                            break;
                        }
                        if (tempAnim != 0) {
                            nf.DeleteAnimation(tempAnim);
                        }
                    }
                }
            }

            if (cameraAnim == 0) {
                logger.warn("[被邀请者] 未找到相机 VMD");
                if (mergedAnim != 0) {
                    nf.DeleteAnimation(mergedAnim);
                }
                return false;
            }

            long modelHandle = 0;
            String modelName = null;
            if (mc.player != null && mergedAnim != 0) {
                String playerName = mc.player.getName().getString();
                modelName = com.shiroha.mmdskin.ui.config.ModelSelectorConfig.getInstance().getSelectedModel();
                if (modelName != null && !modelName.isEmpty()) {
                    com.shiroha.mmdskin.renderer.model.MMDModelManager.Model modelData =
                            com.shiroha.mmdskin.renderer.model.MMDModelManager.GetModel(modelName, playerName);
                    if (modelData != null) {
                        modelHandle = modelData.model.getModelHandle();
                        MmdSkinRendererPlayerHelper.startStageAnimation(modelData, mergedAnim);
                    }
                }
            }

            String audioPath = findAudioInPack(stageDir);
            if (useHostCamera) {
                controller.enterWatchMode(StageInviteManager.getInstance().getWatchingHostUUID());
                controller.setWatchCamera(cameraAnim, heightOffset);
                controller.setWatchMotion(mergedAnim, modelHandle, modelName);
                if (audioPath != null && !audioPath.isEmpty()) {
                    controller.loadWatchAudio(audioPath);
                }
                return true;
            }

            boolean started = controller.startStage(
                    mergedAnim != 0 ? mergedAnim : cameraAnim,
                    cameraAnim,
                    StageConfig.getInstance().cinematicMode,
                    modelHandle,
                    modelName,
                    audioPath,
                    heightOffset
            );
            if (!started) {
                if (mergedAnim != 0) {
                    nf.DeleteAnimation(mergedAnim);
                }
                if (cameraAnim != 0 && cameraAnim != mergedAnim) {
                    nf.DeleteAnimation(cameraAnim);
                }
                logger.warn("[被邀请者] startStage 失败");
            }
            return started;
        } catch (Exception e) {
            logger.error("[被邀请者] 启动舞台失败", e);
            if (mergedAnim != 0) {
                nf.DeleteAnimation(mergedAnim);
            }
            if (cameraAnim != 0 && cameraAnim != mergedAnim) {
                nf.DeleteAnimation(cameraAnim);
            }
            return false;
        }
    }

    private static boolean isSafeName(String name) {
        return name != null && !name.contains("..") && !name.contains("/") && !name.contains("\\");
    }

    private static String findAudioInPack(File stageDir) {
        File[] audioFiles = stageDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav");
        });
        if (audioFiles != null && audioFiles.length > 0) {
            return audioFiles[0].getAbsolutePath();
        }
        return null;
    }

    private static void handleLegacy(UUID senderUUID, String data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        UUID selfUUID = mc.player.getUUID();
        String[] parts = data.split("\\|", 3);
        if (parts.length < 2) {
            logger.warn("[多人舞台] 收到无法识别的消息，已忽略");
            return;
        }

        String action = parts[0];
        StageInviteManager manager = StageInviteManager.getInstance();

        if ("SYNC_FRAME".equals(action)) {
            try {
                handleFrameSync(senderUUID, manager.getSessionId(), Float.parseFloat(parts[1]), manager);
            } catch (NumberFormatException e) {
                logger.warn("[帧同步] 无效帧号: {}", parts[1]);
            }
            return;
        }

        UUID targetUUID;
        try {
            targetUUID = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            logger.warn("[多人舞台] 旧协议目标 UUID 非法: {}", parts[1]);
            return;
        }

        if (!selfUUID.equals(targetUUID)) {
            return;
        }

        switch (action) {
            case "INVITE" -> manager.onInviteReceived(senderUUID, null);
            case "INVITE_CANCEL" -> manager.onInviteCancelled(senderUUID, manager.getSessionId());
            case "ACCEPT" -> manager.onInviteReply(senderUUID, manager.getSessionId(), StageSessionMessage.Reply.ACCEPT);
            case "DECLINE" -> manager.onInviteReply(senderUUID, manager.getSessionId(), StageSessionMessage.Reply.DECLINE);
            case "READY" -> {
                boolean useHostCamera = parts.length >= 3 && parts[2].endsWith("1");
                manager.onMemberReady(senderUUID, manager.getSessionId(), true, useHostCamera);
            }
            case "LEAVE" -> manager.onMemberLeft(senderUUID, manager.getSessionId());
            case "SESSION_DISSOLVE" -> handleSessionDissolve(senderUUID, manager.getSessionId(), mc, manager);
            case "WATCH_START" -> {
                if (parts.length >= 3) {
                    handleLegacyWatchStart(senderUUID, parts[2], mc, manager);
                }
            }
            case "WATCH_END" -> handleWatchEnd(senderUUID, manager.getSessionId(), manager);
            default -> logger.warn("[多人舞台] 未知旧协议动作: {}", action);
        }
    }

    private static void handleLegacyWatchStart(UUID hostUUID, String stageData,
                                               Minecraft mc, StageInviteManager manager) {
        float startFrame = 0.0f;
        float hostHeightOffset = 0.0f;
        String cleanStageData = stageData;

        int frameIdx = cleanStageData.lastIndexOf("|FRAME:");
        if (frameIdx >= 0) {
            try {
                startFrame = Float.parseFloat(cleanStageData.substring(frameIdx + 7));
            } catch (NumberFormatException ignored) {
            }
            cleanStageData = cleanStageData.substring(0, frameIdx);
        }

        int heightIdx = cleanStageData.lastIndexOf("|HEIGHT:");
        if (heightIdx >= 0) {
            try {
                hostHeightOffset = Float.parseFloat(cleanStageData.substring(heightIdx + 8));
            } catch (NumberFormatException ignored) {
            }
            cleanStageData = cleanStageData.substring(0, heightIdx);
        }

        StageSessionMessage message = new StageSessionMessage(StageSessionMessage.Type.WATCH_START);
        message.stageData = cleanStageData;
        message.heightOffset = hostHeightOffset;
        message.frame = startFrame;

        if (manager.isWatchingStage()
                && hostUUID.equals(manager.getWatchingHostUUID())
                && cleanStageData.equals(manager.getWatchingStageData())) {
            return;
        }

        handleWatchStart(hostUUID, manager.getSessionId(), message, mc, manager);
    }
}
