package com.shiroha.skinlayers3d.forge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.shiroha.skinlayers3d.SkinLayers3D;
import com.shiroha.skinlayers3d.forge.register.SkinLayers3DRegisterCommon;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(SkinLayers3D.MOD_ID)
public class SkinLayers3DForge {
    public static final Logger logger = LogManager.getLogger();

    public SkinLayers3DForge() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::preInit);
    }

    public void preInit(FMLCommonSetupEvent event) {
        logger.info("SkinLayers3D 预初始化开始...");
        SkinLayers3DRegisterCommon.Register();
        logger.info("SkinLayers3D 预初始化成功");
    }
}
