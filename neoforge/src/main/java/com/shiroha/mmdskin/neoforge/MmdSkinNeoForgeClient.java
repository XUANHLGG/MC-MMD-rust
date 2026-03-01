package com.shiroha.mmdskin.neoforge;

import com.shiroha.mmdskin.MmdSkin;
import com.shiroha.mmdskin.MmdSkinClient;
import com.shiroha.mmdskin.neoforge.config.MmdSkinConfig;
import com.shiroha.mmdskin.neoforge.maid.MaidRenderEventHandler;
import com.shiroha.mmdskin.neoforge.maid.MaidSyncEventHandler;
import com.shiroha.mmdskin.neoforge.register.MmdSkinRegisterClient;
import com.shiroha.mmdskin.renderer.model.MMDModelOpenGL;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 客户端初始化
 * 
 * 重构说明：
 * - 初始化统一配置管理器
 * - 使用 ConfigManager 访问配置
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD, modid = MmdSkin.MOD_ID)
public class MmdSkinNeoForgeClient {
    /**
     * MOD 事件：注册按键映射（在 FMLClientSetupEvent 之前触发）
     */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        MmdSkinRegisterClient.onRegisterKeyMappings(event);
    }
    
    /**
     * MOD 事件：注册实体渲染器（在 FMLClientSetupEvent 之前触发）
     */
    @SubscribeEvent
    public static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        MmdSkinRegisterClient.onRegisterEntityRenderers(event);
    }
    
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        MmdSkinConfig.init();
        MmdSkinClient.initClient();
        MmdSkinRegisterClient.Register();
        MMDModelOpenGL.isMMDShaderEnabled = com.shiroha.mmdskin.config.ConfigManager.isMMDShaderEnabled();
        NeoForge.EVENT_BUS.register(new MaidRenderEventHandler());
        NeoForge.EVENT_BUS.register(MaidSyncEventHandler.class);
    }
}
