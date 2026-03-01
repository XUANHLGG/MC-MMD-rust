package com.shiroha.mmdskin.fabric.network;

import java.util.UUID;

import com.shiroha.mmdskin.maid.MaidMMDModelManager;
import com.shiroha.mmdskin.renderer.animation.PendingAnimSignalCache;
import com.shiroha.mmdskin.renderer.render.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.renderer.render.MorphSyncHelper;
import com.shiroha.mmdskin.renderer.render.StageAnimSyncHelper;
import com.shiroha.mmdskin.ui.network.NetworkOpCode;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Fabric 网络包发送与客户端处理
 */
public class MmdSkinNetworkPack {

    public static void sendToServer(int opCode, UUID playerUUID, int arg0) {
        ClientPlayNetworking.send(MmdSkinPayload.createInt(opCode, playerUUID, arg0));
    }

    public static void sendToServer(int opCode, UUID playerUUID, String animId) {
        ClientPlayNetworking.send(MmdSkinPayload.createString(opCode, playerUUID, animId));
    }

    public static void sendToServer(int opCode, UUID playerUUID, int entityId, String data) {
        ClientPlayNetworking.send(MmdSkinPayload.createMaid(opCode, playerUUID, entityId, data));
    }

    public static void sendBinaryToServer(int opCode, UUID playerUUID, byte[] data) {
        ClientPlayNetworking.send(MmdSkinPayload.createBinary(opCode, playerUUID, data));
    }

    public static void handlePayload(MmdSkinPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || payload.playerUUID().equals(mc.player.getUUID())) return;

        int opCode = payload.opCode();
        UUID playerUUID = payload.playerUUID();

        if (NetworkOpCode.isEntityStringPayload(opCode)) {
            handleMaid(opCode, playerUUID, payload.entityId(), payload.stringArg());
        } else if (NetworkOpCode.isStringPayload(opCode)) {
            handleString(opCode, playerUUID, payload.stringArg());
        } else {
            handleInt(opCode, playerUUID, payload.intArg());
        }
    }

    private static void handleInt(int opCode, UUID playerUUID, int arg0) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (opCode == NetworkOpCode.RESET_PHYSICS) {
            Player target = mc.level.getPlayerByUUID(playerUUID);
            if (target != null) {
                MmdSkinRendererPlayerHelper.ResetPhysics(target);
            } else {
                PendingAnimSignalCache.put(playerUUID, PendingAnimSignalCache.SignalType.RESET);
            }
        }
    }

    private static void handleString(int opCode, UUID playerUUID, String data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Player target = mc.level.getPlayerByUUID(playerUUID);
        switch (opCode) {
            case NetworkOpCode.CUSTOM_ANIM -> {
                if (target != null) MmdSkinRendererPlayerHelper.CustomAnim(target, data);
            }
            case NetworkOpCode.MODEL_SELECT -> {
                PlayerModelSyncManager.onRemotePlayerModelReceived(playerUUID, data);
            }
            case NetworkOpCode.MORPH_SYNC -> {
                if (target != null) MorphSyncHelper.applyRemoteMorph(target, data);
            }
            case NetworkOpCode.STAGE_START -> {
                if (target != null) StageAnimSyncHelper.startStageAnim(target, data);
            }
            case NetworkOpCode.STAGE_END -> {
                if (target != null) {
                    StageAnimSyncHelper.endStageAnim(target);
                } else {
                    PendingAnimSignalCache.put(playerUUID, PendingAnimSignalCache.SignalType.STAGE_END);
                }
            }
            case NetworkOpCode.STAGE_AUDIO -> {
                if (target != null) MmdSkinRendererPlayerHelper.StageAudioPlay(target, data);
            }
            case NetworkOpCode.STAGE_MULTI -> {
                com.shiroha.mmdskin.ui.network.StageMultiHandler.handle(playerUUID, data);
            }
            default -> {}
        }
    }

    private static void handleMaid(int opCode, UUID playerUUID, int entityId, String data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Entity maidEntity = mc.level.getEntity(entityId);
        if (maidEntity == null) return;

        switch (opCode) {
            case NetworkOpCode.MAID_MODEL -> MaidMMDModelManager.bindModel(maidEntity.getUUID(), data);
            case NetworkOpCode.MAID_ACTION -> MaidMMDModelManager.playAnimation(maidEntity.getUUID(), data);
            default -> {}
        }
    }
}
