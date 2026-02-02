package com.shiroha.skinlayers3d.renderer.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Iris 光影模组兼容层
 * 
 * 核心功能：
 * 1. 检测 Iris 是否加载并激活
 * 2. 获取 Iris 的 G-buffer framebuffer，确保渲染输出进入正确的缓冲区
 * 3. 正确设置渲染阶段（ENTITIES），让 Iris 的光影能正确处理我们的输出
 * 4. 处理自定义渲染前后的 OpenGL 状态保存/恢复
 * 
 * 关键点：
 * - Iris 通过 Mixin 拦截 GlStateManager._glUseProgram，必须使用它来恢复着色器
 * - GPU 蒙皮使用的 SSBO 绑定点可能与 Iris 冲突，需要解绑
 * - 自定义着色器渲染后必须完全清理状态
 * - 必须绑定 Iris 的 framebuffer 才能让光影正确渲染我们的材质
 */
public class IrisCompat {
    private static final Logger logger = LogManager.getLogger();
    
    // Iris 检测状态
    private static boolean irisChecked = false;
    private static boolean irisPresent = false;
    @SuppressWarnings("unused") // 用于调试和将来扩展
    private static boolean irisReflectionFailed = false;
    
    // Iris 反射缓存
    private static Class<?> irisClass;
    private static Class<?> irisApiClass;
    private static Class<?> pipelineManagerClass;
    private static Class<?> worldRenderingPipelineClass;
    private static Class<?> irisRenderingPipelineClass;
    private static Class<?> gbufferProgramsClass;
    private static Class<?> worldRenderingPhaseClass;
    private static Class<?> renderTargetsClass;
    private static Class<?> glFramebufferClass;
    
    private static Method getPipelineManagerMethod;
    private static Method getPipelineNullableMethod;
    private static Method isShaderPackInUseMethod;
    private static Field renderTargetsField;  // 字段反射，因为没有 getter 方法
    private static Field depthSourceFbField;  // 字段反射，因为没有 getter 方法
    @SuppressWarnings("unused") // 预留用于 GbufferPrograms 集成
    private static Method beginEntitiesMethod;
    @SuppressWarnings("unused") // 预留用于 GbufferPrograms 集成
    private static Method endEntitiesMethod;
    private static Method setPhaseMethod;
    private static Method getPhaseMethod;
    private static Method framebufferBindMethod;
    private static Method shouldOverrideShadersMethod;
    private static Object entitiesPhase;
    private static Object nonePhase;
    
    // 保存的状态
    private static int savedVao = 0;
    private static int savedArrayBuffer = 0;
    private static int savedElementBuffer = 0;
    private static int savedProgram = 0;
    private static int savedActiveTexture = 0;
    private static int savedFramebuffer = 0;
    private static int savedDrawFramebuffer = 0;
    private static int savedReadFramebuffer = 0;
    
    // Iris framebuffer 缓存
    private static Object cachedPipeline = null;
    private static int irisEntityFramebuffer = 0;
    private static boolean inIrisEntityPhase = false;
    
    // GPU 蒙皮使用的 SSBO 绑定点
    private static final int BONE_MATRIX_SSBO_BINDING = 0;
    
    /**
     * 开始自定义渲染前调用
     * 保存当前 OpenGL 状态，防止与 Iris 冲突
     */
    public static void beginCustomRendering() {
        // 保存当前绑定的 VAO
        savedVao = GL46C.glGetInteger(GL46C.GL_VERTEX_ARRAY_BINDING);
        // 保存当前绑定的缓冲区
        savedArrayBuffer = GL46C.glGetInteger(GL46C.GL_ARRAY_BUFFER_BINDING);
        savedElementBuffer = GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        // 保存当前着色器程序
        savedProgram = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        // 保存当前活动纹理单元
        savedActiveTexture = GL46C.glGetInteger(GL46C.GL_ACTIVE_TEXTURE);
    }
    
    /**
     * 自定义渲染结束后调用
     * 恢复 OpenGL 状态，确保 Iris 能正常工作
     */
    public static void endCustomRendering() {
        // === 关键：使用 GlStateManager 解绑着色器，让 Iris 能正确拦截 ===
        GlStateManager._glUseProgram(0);
        
        // 解绑 SSBO（GPU 蒙皮使用的骨骼矩阵缓冲区）
        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BONE_MATRIX_SSBO_BINDING, 0);
        
        // 解绑 VAO
        GlStateManager._glBindVertexArray(0);
        
        // 解绑缓冲区
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, 0);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, 0);
        
        // 恢复活动纹理单元
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        
        // 重置 Minecraft 的缓冲区上传器状态
        BufferUploader.reset();
        
        // 清除当前着色器引用
        if (RenderSystem.getShader() != null) {
            RenderSystem.getShader().clear();
        }
    }
    
    /**
     * GPU 蒙皮专用：完全恢复状态
     * 在使用自定义着色器后调用
     */
    public static void endGpuSkinningRendering() {
        // 使用 GlStateManager 解绑着色器（Iris 会拦截这个调用）
        GlStateManager._glUseProgram(0);
        
        // 解绑 SSBO 绑定点 0-3（防止与 Iris 的 SSBO 冲突）
        for (int i = 0; i < 4; i++) {
            GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, i, 0);
        }
        
        // 解绑 VAO
        GlStateManager._glBindVertexArray(0);
        
        // 解绑缓冲区
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, 0);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, 0);
        
        // 恢复纹理单元
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
        RenderSystem.bindTexture(0);
        
        // 重置缓冲区上传器
        BufferUploader.reset();
        
        // 清除着色器引用
        if (RenderSystem.getShader() != null) {
            RenderSystem.getShader().clear();
        }
    }
    
    /**
     * 完全恢复到之前保存的状态
     */
    public static void restoreFullState() {
        // 恢复 VAO
        GlStateManager._glBindVertexArray(savedVao);
        // 恢复缓冲区
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, savedArrayBuffer);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, savedElementBuffer);
        // 使用 GlStateManager 恢复着色器程序
        GlStateManager._glUseProgram(savedProgram);
        // 恢复活动纹理单元
        GL46C.glActiveTexture(savedActiveTexture);
    }
    
    // ==================== Iris G-buffer 集成 ====================
    
    /**
     * 初始化 Iris 反射（懒加载）
     * 通过反射访问 Iris API，避免硬依赖
     */
    private static void initIrisReflection() {
        if (irisChecked) return;
        irisChecked = true;
        
        try {
            // 尝试加载 Iris 主类
            irisClass = Class.forName("net.irisshaders.iris.Iris");
            irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            pipelineManagerClass = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            worldRenderingPipelineClass = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPipeline");
            irisRenderingPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            gbufferProgramsClass = Class.forName("net.irisshaders.iris.layer.GbufferPrograms");
            worldRenderingPhaseClass = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
            renderTargetsClass = Class.forName("net.irisshaders.iris.targets.RenderTargets");
            glFramebufferClass = Class.forName("net.irisshaders.iris.gl.framebuffer.GlFramebuffer");
            
            // 获取 Iris 方法
            getPipelineManagerMethod = irisClass.getMethod("getPipelineManager");
            getPipelineNullableMethod = pipelineManagerClass.getMethod("getPipelineNullable");
            
            // IrisApi.getInstance().isShaderPackInUse()
            Method getInstanceMethod = irisApiClass.getMethod("getInstance");
            getInstanceMethod.invoke(null); // 验证 API 可用
            isShaderPackInUseMethod = irisApiClass.getMethod("isShaderPackInUse");
            
            // GbufferPrograms 方法
            beginEntitiesMethod = gbufferProgramsClass.getMethod("beginEntities");
            endEntitiesMethod = gbufferProgramsClass.getMethod("endEntities");
            
            // WorldRenderingPipeline 方法
            setPhaseMethod = worldRenderingPipelineClass.getMethod("setPhase", worldRenderingPhaseClass);
            getPhaseMethod = worldRenderingPipelineClass.getMethod("getPhase");
            
            // IrisRenderingPipeline.shouldOverrideShaders()
            shouldOverrideShadersMethod = irisRenderingPipelineClass.getMethod("shouldOverrideShaders");
            
            // 获取 WorldRenderingPhase 枚举值
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Enum> phaseEnumClass = (Class<Enum>) worldRenderingPhaseClass;
            @SuppressWarnings("unchecked")
            Enum<?> entities = Enum.valueOf(phaseEnumClass, "ENTITIES");
            @SuppressWarnings("unchecked")
            Enum<?> none = Enum.valueOf(phaseEnumClass, "NONE");
            entitiesPhase = entities;
            nonePhase = none;
            
            // GlFramebuffer.bind()
            framebufferBindMethod = glFramebufferClass.getMethod("bind");
            
            // RenderTargets 字段（私有字段，没有 getter）
            renderTargetsField = irisRenderingPipelineClass.getDeclaredField("renderTargets");
            renderTargetsField.setAccessible(true);
            
            // depthSourceFb 字段（私有字段，没有 getter）
            depthSourceFbField = renderTargetsClass.getDeclaredField("depthSourceFb");
            depthSourceFbField.setAccessible(true);
            
            irisPresent = true;
            logger.info("Iris 检测成功，已启用 G-buffer 集成");
            
        } catch (ClassNotFoundException e) {
            // Iris 未安装，这是正常情况
            irisPresent = false;
            logger.debug("Iris 未安装，跳过 G-buffer 集成");
        } catch (Exception e) {
            // 反射失败，禁用集成
            irisPresent = false;
            irisReflectionFailed = true;
            logger.warn("Iris 反射初始化失败，禁用 G-buffer 集成: {}", e.getMessage());
        }
    }
    
    /**
     * 检查 Iris 是否已加载
     */
    public static boolean isIrisLoaded() {
        initIrisReflection();
        return irisPresent;
    }
    
    /**
     * 检查 Iris 着色器包是否正在使用
     */
    public static boolean isShaderPackInUse() {
        if (!isIrisLoaded()) return false;
        
        try {
            Method getInstanceMethod = irisApiClass.getMethod("getInstance");
            Object apiInstance = getInstanceMethod.invoke(null);
            return (Boolean) isShaderPackInUseMethod.invoke(apiInstance);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 获取 Iris 当前的渲染管线
     */
    private static Object getIrisPipeline() {
        if (!isIrisLoaded()) return null;
        
        try {
            Object pipelineManager = getPipelineManagerMethod.invoke(null);
            return getPipelineNullableMethod.invoke(pipelineManager);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 检查 Iris 是否应该覆盖着色器（即是否在世界渲染中且主缓冲区已绑定）
     */
    public static boolean shouldIrisOverrideShaders() {
        Object pipeline = getIrisPipeline();
        if (pipeline == null) return false;
        
        try {
            if (irisRenderingPipelineClass.isInstance(pipeline)) {
                return (Boolean) shouldOverrideShadersMethod.invoke(pipeline);
            }
        } catch (Exception e) {
            // 忽略
        }
        return false;
    }
    
    /**
     * 开始 GPU 蒙皮渲染（Iris 兼容版本）
     * 
     * 如果 Iris 激活：
     * 1. 保存当前 framebuffer 状态
     * 2. 绑定 Iris 的实体 G-buffer framebuffer
     * 3. 设置渲染阶段为 ENTITIES
     * 
     * @return true 如果成功设置了 Iris 集成
     */
    public static boolean beginGpuSkinningWithIris() {
        // 先保存基本 OpenGL 状态
        beginCustomRendering();
        
        // 额外保存 framebuffer 状态
        savedFramebuffer = GL46C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        savedDrawFramebuffer = GL46C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        savedReadFramebuffer = GL46C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        
        // 如果 Iris 未激活或未使用着色器包，直接返回
        if (!isShaderPackInUse() || !shouldIrisOverrideShaders()) {
            inIrisEntityPhase = false;
            return false;
        }
        
        try {
            Object pipeline = getIrisPipeline();
            if (pipeline == null || !irisRenderingPipelineClass.isInstance(pipeline)) {
                inIrisEntityPhase = false;
                return false;
            }
            
            // 检查当前渲染阶段，如果已经在 ENTITIES 阶段就不要重复设置
            Object currentPhase = getPhaseMethod.invoke(pipeline);
            if (currentPhase == entitiesPhase) {
                // 已经在实体渲染阶段，直接使用当前 framebuffer
                inIrisEntityPhase = false; // 不需要我们来结束阶段
                return true;
            }
            
            // 获取 Iris 的 RenderTargets（通过字段反射）
            Object renderTargets = renderTargetsField.get(pipeline);
            if (renderTargets == null) {
                inIrisEntityPhase = false;
                return false;
            }
            
            // 获取深度源 framebuffer（通过字段反射）
            Object depthSourceFb = depthSourceFbField.get(renderTargets);
            if (depthSourceFb != null) {
                // 绑定 Iris 的 framebuffer
                framebufferBindMethod.invoke(depthSourceFb);
                irisEntityFramebuffer = GL46C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
            }
            
            // 设置渲染阶段为 ENTITIES
            // 注意：我们不调用 beginEntities() 因为那会触发 Iris 的阶段管理逻辑
            // 而是直接设置 phase，这样 Iris 的着色器选择逻辑会认为我们在渲染实体
            setPhaseMethod.invoke(pipeline, entitiesPhase);
            inIrisEntityPhase = true;
            
            cachedPipeline = pipeline;
            return true;
            
        } catch (Exception e) {
            logger.debug("Iris G-buffer 集成失败: {}", e.getMessage());
            inIrisEntityPhase = false;
            return false;
        }
    }
    
    /**
     * 结束 GPU 蒙皮渲染（Iris 兼容版本）
     * 
     * 恢复 Iris 状态和 framebuffer
     */
    public static void endGpuSkinningWithIris() {
        // 先执行标准的 GPU 蒙皮状态清理
        endGpuSkinningRendering();
        
        // 如果之前设置了 Iris 实体阶段，需要恢复
        if (inIrisEntityPhase && cachedPipeline != null) {
            try {
                // 恢复渲染阶段为 NONE
                setPhaseMethod.invoke(cachedPipeline, nonePhase);
            } catch (Exception e) {
                // 忽略
            }
            inIrisEntityPhase = false;
            cachedPipeline = null;
        }
        
        // 恢复原来的 framebuffer
        if (savedDrawFramebuffer != 0 || savedReadFramebuffer != 0) {
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, savedDrawFramebuffer);
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, savedReadFramebuffer);
        } else if (savedFramebuffer != 0) {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, savedFramebuffer);
        }
    }
    
    /**
     * 获取当前应该使用的 framebuffer ID
     * 如果 Iris 激活，返回 Iris 的 G-buffer framebuffer
     * 否则返回 0（使用默认 framebuffer）
     */
    public static int getTargetFramebuffer() {
        if (inIrisEntityPhase && irisEntityFramebuffer != 0) {
            return irisEntityFramebuffer;
        }
        return 0;
    }
    
    /**
     * 检查是否正在 Iris 实体渲染阶段
     */
    public static boolean isInIrisEntityPhase() {
        return inIrisEntityPhase;
    }
}
