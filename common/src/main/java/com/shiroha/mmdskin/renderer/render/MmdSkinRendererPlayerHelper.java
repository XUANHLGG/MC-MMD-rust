package com.shiroha.mmdskin.renderer.render;

import com.shiroha.mmdskin.renderer.core.IMMDModel;
import com.shiroha.mmdskin.renderer.animation.MMDAnimManager;
import com.shiroha.mmdskin.renderer.model.MMDModelManager;
import net.minecraft.world.entity.player.Player;

/**
 * 玩家动画控制辅助类 (SRP - 单一职责原则)
 * 
 * 仅负责玩家的自定义动画播放和物理重置。
 * 舞台动画同步委托给 {@link StageAnimSyncHelper}，
 * 表情同步委托给 {@link MorphSyncHelper}。
 */
public final class MmdSkinRendererPlayerHelper {

    private MmdSkinRendererPlayerHelper() {
    }

    /**
     * 重置玩家物理和动画状态
     */
    public static void ResetPhysics(Player player) {
        PlayerModelResolver.Result resolved = PlayerModelResolver.resolve(player);
        if (resolved == null) return;
        
        MMDModelManager.Model mwed = resolved.model();
        IMMDModel model = mwed.model;
        mwed.entityData.playCustomAnim = false;
        model.changeAnim(MMDAnimManager.GetAnimModel(model, "idle"), 0);
        model.changeAnim(0, 1);
        model.changeAnim(0, 2);
        model.resetPhysics();
    }

    /**
     * 播放自定义动画
     */
    public static void CustomAnim(Player player, String id) {
        PlayerModelResolver.Result resolved = PlayerModelResolver.resolve(player);
        if (resolved == null) return;
        
        MMDModelManager.Model mwed = resolved.model();
        IMMDModel model = mwed.model;
        mwed.entityData.playCustomAnim = true;
        model.changeAnim(MMDAnimManager.GetAnimModel(model, id), 0);
        model.changeAnim(0, 1);
        model.changeAnim(0, 2);
    }
    
    /**
     * 断线时清理所有远程同步状态
     * 由 Fabric/Forge 的 DISCONNECT 事件处理器调用
     */
    public static void onDisconnect() {
        StageAnimSyncHelper.onDisconnect();
    }
}
