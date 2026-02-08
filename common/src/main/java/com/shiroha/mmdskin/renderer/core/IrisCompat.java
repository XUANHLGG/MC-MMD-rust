package com.shiroha.mmdskin.renderer.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * Iris 光影模组运行时兼容检测
 * 
 * 通过反射检测 Iris API，避免硬依赖。
 * 用于判断当前是否有 Iris 光影包激活，以便渲染路径做相应适配。
 */
public class IrisCompat {
    private static final Logger logger = LogManager.getLogger();
    
    private static Boolean irisPresent = null;
    private static Method isShaderPackInUseMethod = null;
    private static Object irisApiInstance = null;
    
    /**
     * 检测 Iris 光影包是否正在使用中
     * 
     * @return true 表示 Iris 已加载且有光影包正在生效
     */
    public static boolean isIrisShaderActive() {
        if (irisPresent == null) {
            detectIris();
        }
        if (!irisPresent) return false;
        
        try {
            return (Boolean) isShaderPackInUseMethod.invoke(irisApiInstance);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 通过反射探测 Iris API 是否可用
     */
    private static void detectIris() {
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getInstanceMethod = irisApiClass.getMethod("getInstance");
            irisApiInstance = getInstanceMethod.invoke(null);
            isShaderPackInUseMethod = irisApiClass.getMethod("isShaderPackInUse");
            irisPresent = true;
            logger.info("[IrisCompat] 检测到 Iris API，已启用兼容模式");
        } catch (ClassNotFoundException e) {
            irisPresent = false;
            logger.info("[IrisCompat] 未检测到 Iris，跳过兼容");
        } catch (Exception e) {
            irisPresent = false;
            logger.warn("[IrisCompat] Iris API 检测异常", e);
        }
    }
    
    /**
     * 重置检测状态（用于热重载场景）
     */
    public static void reset() {
        irisPresent = null;
        isShaderPackInUseMethod = null;
        irisApiInstance = null;
    }
}
