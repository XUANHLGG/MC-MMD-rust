package com.shiroha.mmdskin.fabric.register;

import com.shiroha.mmdskin.fabric.network.MmdSkinPayload;
import com.shiroha.mmdskin.ui.network.NetworkOpCode;
import com.shiroha.mmdskin.ui.network.ServerModelRegistry;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 * Fabric 服务端网络注册
 */
public class MmdSkinRegisterCommon {
    private static final Logger logger = LogManager.getLogger();

    public static void Register() {
        PayloadTypeRegistry.playC2S().register(MmdSkinPayload.TYPE, MmdSkinPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MmdSkinPayload.TYPE, MmdSkinPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MmdSkinPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            UUID realUUID = player.getUUID();

            // UUID 鉴权
            if (!realUUID.equals(payload.playerUUID())) {
                logger.warn("UUID 不匹配，丢弃数据包: claimed={}, real={}", payload.playerUUID(), realUUID);
                return;
            }

            int opCode = payload.opCode();

            // 模型选择时更新服务端注册表
            if (opCode == NetworkOpCode.MODEL_SELECT) {
                ServerModelRegistry.updateModel(realUUID, payload.stringArg());
            }

            // REQUEST_ALL_MODELS：回传所有已注册模型给请求者，不转发
            if (opCode == NetworkOpCode.REQUEST_ALL_MODELS) {
                context.server().execute(() -> {
                    ServerModelRegistry.sendAllTo((modelOwnerUUID, modelName) -> {
                        MmdSkinPayload reply = MmdSkinPayload.createString(
                                NetworkOpCode.MODEL_SELECT, modelOwnerUUID, modelName);
                        ServerPlayNetworking.send(player, reply);
                    });
                });
                return;
            }

            // 用真实 UUID 重建 Payload 后转发
            MmdSkinPayload corrected = new MmdSkinPayload(
                    opCode, realUUID, payload.intArg(), payload.entityId(),
                    payload.stringArg(), payload.binaryData());

            context.server().execute(() -> {
                for (ServerPlayer serverPlayer : PlayerLookup.all(context.server())) {
                    if (!serverPlayer.equals(player)) {
                        ServerPlayNetworking.send(serverPlayer, corrected);
                    }
                }
            });
        });

        // 玩家离线时清理服务端注册表
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> ServerModelRegistry.onPlayerLeave(handler.getPlayer().getUUID()));
    }
}
