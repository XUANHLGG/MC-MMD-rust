package com.shiroha.mmdskin.mixin.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.neoforge.YsmCompat;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ItemInHandRenderer Mixin — 第一人称手臂隐藏
 * 
 * 在第一人称 MMD 模型模式下，跳过原版手臂和手持物品的渲染。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    
    @Inject(
        method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onRenderHandsWithItems(float partialTick, PoseStack poseStack, BufferSource bufferSource, LocalPlayer player, int packedLight, CallbackInfo ci) {
        String playerName = player.getName().getString();
        String selectedModel = PlayerModelSyncManager.getPlayerModel(player.getUUID(), playerName, true);
        
        // 检查是否为 MMD 默认/原版模型状态
        boolean isMmdDefault = selectedModel == null || selectedModel.isEmpty() || selectedModel.equals("默认 (原版渲染)");
        boolean isMmdActive = !isMmdDefault;
        boolean isVanilaMmdModel = isMmdActive && (selectedModel.equals("VanilaModel") || selectedModel.equalsIgnoreCase("vanila"));

        // 核心逻辑调整：
        // 无论 MMD 是否激活，只要 YSM 插件表示“我要渲染手臂”（即 isDisableSelfHands 为 false），
        // 我们就绝对不能执行 ci.cancel()。
        if (YsmCompat.isYsmModelActive(player)) {
            if (YsmCompat.isDisableSelfHands()) {
                ci.cancel();
            }
            return; // 一旦 YSM 接管，MMD 就不再干预手臂渲染（避免冲突）
        }

        // 如果没有 YSM 接管，则由 MMD 决定：
        // 只有在选了非原版 MMD 模型且启用了第一人称模型时，才取消原版手臂渲染。
        if (isMmdActive && !isVanilaMmdModel && ConfigManager.isFirstPersonModelEnabled()) {
            ci.cancel();
        }
    }
}
