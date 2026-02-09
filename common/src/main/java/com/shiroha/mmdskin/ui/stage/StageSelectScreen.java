package com.shiroha.mmdskin.ui.stage;

import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.config.PathConstants;
import com.shiroha.mmdskin.config.StageConfig;
import com.shiroha.mmdskin.config.StagePack;
import com.shiroha.mmdskin.renderer.camera.MMDCameraController;
import com.shiroha.mmdskin.renderer.model.MMDModelManager;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 舞台模式选择界面
 * 左侧：舞台包列表
 * 右侧：选中舞台包的 VMD 文件详情
 * 底部：影院模式开关 + 开始/取消按钮
 */
public class StageSelectScreen extends Screen {
    private static final Logger logger = LogManager.getLogger();
    
    // 布局常量
    private static final int PANEL_MARGIN = 8;
    private static final int HEADER_HEIGHT = 30;
    private static final int FOOTER_HEIGHT = 56;
    private static final int ITEM_HEIGHT = 16;
    private static final int ITEM_SPACING = 1;
    private static final int GAP = 8;
    
    // 配色
    private static final int COLOR_BG = 0xD0101418;
    private static final int COLOR_PANEL_BG = 0xC0181C22;
    private static final int COLOR_BORDER = 0xFF2A3A4A;
    private static final int COLOR_ACCENT = 0xFF60A0D0;
    private static final int COLOR_TEXT = 0xFFDDDDDD;
    private static final int COLOR_TEXT_DIM = 0xFF888888;
    private static final int COLOR_ITEM_HOVER = 0x30FFFFFF;
    private static final int COLOR_ITEM_SELECTED = 0x4060A0D0;
    private static final int COLOR_BTN_START = 0xFF40A060;
    private static final int COLOR_BTN_CANCEL = 0xFF666666;
    private static final int COLOR_TOGGLE_ON = 0xFF60A0D0;
    private static final int COLOR_TOGGLE_OFF = 0xFF444444;
    private static final int COLOR_TAG_AUDIO = 0xFF60B0E0;
    
    // 舞台包列表
    private List<StagePack> stagePacks = new ArrayList<>();
    
    // 选择状态
    private int selectedPack = -1;
    private boolean cinematicMode;
    private boolean stageStarted = false;
    
    // 滚动
    private int packScrollOffset = 0;
    private int packMaxScroll = 0;
    private int detailScrollOffset = 0;
    private int detailMaxScroll = 0;
    
    // 悬停
    private int hoveredPack = -1;
    private boolean hoverStart = false;
    private boolean hoverCancel = false;
    private boolean hoverToggle = false;
    
    // 布局缓存
    private int leftPanelX, leftPanelW;
    private int rightPanelX, rightPanelW;
    private int panelY, panelH;
    private int listTop, listBottom;
    
    public StageSelectScreen() {
        super(Component.literal("舞台模式"));
        StageConfig config = StageConfig.getInstance();
        this.cinematicMode = config.cinematicMode;
        // 确保目录存在
        PathConstants.ensureStageAnimDir();
        // 扫描舞台包
        stagePacks = StagePack.scan(PathConstants.getStageAnimDir());
        restoreSelection(config);
    }
    
    private void restoreSelection(StageConfig config) {
        for (int i = 0; i < stagePacks.size(); i++) {
            if (stagePacks.get(i).getName().equals(config.lastStagePack)) {
                selectedPack = i;
                break;
            }
        }
    }
    
    private StagePack getSelectedPack() {
        if (selectedPack >= 0 && selectedPack < stagePacks.size()) {
            return stagePacks.get(selectedPack);
        }
        return null;
    }
    
    @Override
    protected void init() {
        super.init();
        // 立即进入舞台模式（切换第三人称 + 相机过渡到展示位置）
        MMDCameraController.getInstance().enterStageMode();
        int totalW = this.width - PANEL_MARGIN * 3;
        leftPanelW = totalW * 2 / 5;
        rightPanelW = totalW - leftPanelW;
        leftPanelX = PANEL_MARGIN;
        rightPanelX = leftPanelX + leftPanelW + PANEL_MARGIN;
        panelY = HEADER_HEIGHT + PANEL_MARGIN;
        panelH = this.height - panelY - FOOTER_HEIGHT - PANEL_MARGIN;
        listTop = panelY + 22;
        listBottom = panelY + panelH - 2;
    }
    
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景
        g.fill(0, 0, this.width, this.height, COLOR_BG);
        
        // 标题
        g.drawCenteredString(this.font, "\uD83C\uDFAC 舞台模式", this.width / 2, 10, COLOR_ACCENT);
        
        // 左侧面板 — 舞台包列表
        renderPanel(g, leftPanelX, panelY, leftPanelW, panelH, "舞台包 (" + stagePacks.size() + ")");
        hoveredPack = -1;
        renderPackList(g, mouseX, mouseY);
        
        // 右侧面板 — 选中包的详情
        StagePack selected = getSelectedPack();
        String detailTitle = selected != null ? selected.getName() : "详情";
        renderPanel(g, rightPanelX, panelY, rightPanelW, panelH, detailTitle);
        renderPackDetail(g, selected, mouseX, mouseY);
        
        // 底部控件
        renderFooter(g, mouseX, mouseY);
        
        super.render(g, mouseX, mouseY, partialTick);
    }
    
    private void renderPanel(GuiGraphics g, int x, int y, int w, int h, String title) {
        // 面板背景
        g.fill(x, y, x + w, y + h, COLOR_PANEL_BG);
        // 边框
        g.fill(x, y, x + w, y + 1, COLOR_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, COLOR_BORDER);
        g.fill(x, y, x + 1, y + h, COLOR_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, COLOR_BORDER);
        // 标题
        g.drawString(this.font, title, x + 6, y + 7, COLOR_ACCENT, false);
        // 分隔线
        g.fill(x + 2, y + 20, x + w - 2, y + 21, COLOR_BORDER);
    }
    
    private void renderPackList(GuiGraphics g, int mouseX, int mouseY) {
        int visibleHeight = listBottom - listTop;
        packMaxScroll = Math.max(0, stagePacks.size() * (ITEM_HEIGHT + ITEM_SPACING) - visibleHeight);
        
        for (int i = 0; i < stagePacks.size(); i++) {
            int itemY = listTop + i * (ITEM_HEIGHT + ITEM_SPACING) - packScrollOffset;
            if (itemY + ITEM_HEIGHT < listTop || itemY > listBottom) continue;
            
            int itemW = leftPanelW - 4;
            int itemX = leftPanelX + 2;
            
            boolean hovered = mouseX >= itemX && mouseX < itemX + itemW && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
            boolean sel = (i == selectedPack);
            
            if (hovered) hoveredPack = i;
            
            // 背景
            if (sel) {
                g.fill(itemX, itemY, itemX + itemW, itemY + ITEM_HEIGHT, COLOR_ITEM_SELECTED);
            } else if (hovered) {
                g.fill(itemX, itemY, itemX + itemW, itemY + ITEM_HEIGHT, COLOR_ITEM_HOVER);
            }
            
            // 包名
            StagePack pack = stagePacks.get(i);
            // 音频标记
            int nameX = itemX + 4;
            if (pack.hasAudio()) {
                g.drawString(this.font, "\u266B", nameX, itemY + 4, COLOR_TAG_AUDIO, false);
                nameX += 10;
            }
            String displayName = truncate(pack.getName(), pack.hasAudio() ? 16 : 18);
            g.drawString(this.font, displayName, nameX, itemY + 4, sel ? COLOR_ACCENT : COLOR_TEXT, false);
            
            // 文件数量 + 相机标记
            String info = pack.getVmdFiles().size() + "个";
            if (pack.hasCameraVmd()) info += " \uD83D\uDCF7";
            g.drawString(this.font, info, itemX + itemW - this.font.width(info) - 4, itemY + 4, COLOR_TEXT_DIM, false);
        }
    }
    
    private void renderPackDetail(GuiGraphics g, StagePack pack, int mouseX, int mouseY) {
        if (pack == null) {
            g.drawString(this.font, "← 选择一个舞台包", rightPanelX + 10, listTop + 10, COLOR_TEXT_DIM, false);
            return;
        }
        
        List<StagePack.VmdFileInfo> files = pack.getVmdFiles();
        List<StagePack.AudioFileInfo> audios = pack.getAudioFiles();
        int totalItems = files.size() + audios.size();
        int visibleHeight = listBottom - listTop;
        detailMaxScroll = Math.max(0, totalItems * (ITEM_HEIGHT + ITEM_SPACING) - visibleHeight);
        
        int row = 0;
        for (int i = 0; i < files.size(); i++) {
            int itemY = listTop + row * (ITEM_HEIGHT + ITEM_SPACING) - detailScrollOffset;
            row++;
            if (itemY + ITEM_HEIGHT < listTop || itemY > listBottom) continue;
            
            int itemX = rightPanelX + 2;
            
            StagePack.VmdFileInfo info = files.get(i);
            
            // 类型标签
            String tag = info.getTypeTag();
            String displayName = truncate(info.name, 22);
            
            g.drawString(this.font, tag, itemX + 4, itemY + 4, COLOR_TEXT, false);
            g.drawString(this.font, displayName, itemX + 4 + this.font.width(tag) + 4, itemY + 4, COLOR_TEXT, false);
        }
        
        // 音频文件
        for (int i = 0; i < audios.size(); i++) {
            StagePack.AudioFileInfo audio = audios.get(i);
            int itemY = listTop + row * (ITEM_HEIGHT + ITEM_SPACING) - detailScrollOffset;
            row++;
            if (itemY + ITEM_HEIGHT < listTop || itemY > listBottom) continue;
            
            int itemX = rightPanelX + 2;
            int itemW = rightPanelW - 4;
            
            String fileName = audio.name;
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0) fileName = fileName.substring(0, dot);
            fileName = truncate(fileName, 18);
            g.drawString(this.font, fileName, itemX + 4, itemY + 4, COLOR_TEXT, false);
            
            String formatTag = "\u266B" + audio.format;
            int tagW = this.font.width(formatTag);
            g.drawString(this.font, formatTag, itemX + itemW - tagW - 4, itemY + 4, COLOR_TAG_AUDIO, false);
        }
    }
    
    private void renderFooter(GuiGraphics g, int mouseX, int mouseY) {
        int footerY = this.height - FOOTER_HEIGHT;
        
        // 影院模式开关
        int toggleX = PANEL_MARGIN + 4;
        int toggleY = footerY + 8;
        int toggleW = 30;
        int toggleH = 14;
        
        hoverToggle = mouseX >= toggleX && mouseX < toggleX + toggleW + 80 && mouseY >= toggleY && mouseY < toggleY + toggleH;
        
        g.fill(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH,
                cinematicMode ? COLOR_TOGGLE_ON : COLOR_TOGGLE_OFF);
        int knobX = cinematicMode ? toggleX + toggleW - 12 : toggleX + 2;
        g.fill(knobX, toggleY + 2, knobX + 10, toggleY + toggleH - 2, 0xFFFFFFFF);
        g.drawString(this.font, Component.translatable("gui.mmdskin.stage.cinematic"), toggleX + toggleW + 6, toggleY + 3, COLOR_TEXT, false);
        
        // 按钮
        int btnW = 70;
        int btnH = 20;
        int btnY = footerY + 4;
        
        // 取消按钮
        int cancelX = this.width - PANEL_MARGIN - btnW;
        hoverCancel = mouseX >= cancelX && mouseX < cancelX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        g.fill(cancelX, btnY, cancelX + btnW, btnY + btnH, hoverCancel ? 0xFF888888 : COLOR_BTN_CANCEL);
        g.drawCenteredString(this.font, "取消", cancelX + btnW / 2, btnY + 6, COLOR_TEXT);
        
        // 开始按钮
        int startX = cancelX - btnW - 8;
        StagePack pack = getSelectedPack();
        boolean canStart = pack != null && pack.hasMotionVmd();
        hoverStart = canStart && mouseX >= startX && mouseX < startX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        int startColor = canStart ? (hoverStart ? 0xFF50C070 : COLOR_BTN_START) : 0xFF333333;
        g.fill(startX, btnY, startX + btnW, btnY + btnH, startColor);
        g.drawCenteredString(this.font, Component.translatable("gui.mmdskin.stage.start"), startX + btnW / 2, btnY + 6, canStart ? 0xFFFFFFFF : COLOR_TEXT_DIM);
        
        // 选择提示
        if (pack != null) {
            String hint = "包: " + pack.getName() + " (" + pack.getVmdFiles().size() + " 文件)";
            g.drawString(this.font, hint, PANEL_MARGIN + 4, footerY + 30, COLOR_TEXT_DIM, false);
        }
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 包列表点击
            if (hoveredPack >= 0) {
                selectedPack = hoveredPack;
                detailScrollOffset = 0;
                return true;
            }
            // 影院模式开关
            if (hoverToggle) {
                cinematicMode = !cinematicMode;
                return true;
            }
            // 开始按钮
            if (hoverStart) {
                startStage();
                return true;
            }
            // 取消按钮
            if (hoverCancel) {
                this.onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int scrollAmount = (int) (-scrollY * (ITEM_HEIGHT + ITEM_SPACING) * 3);
        
        if (mouseX < rightPanelX) {
            // 包列表滚动
            packScrollOffset = Math.max(0, Math.min(packMaxScroll, packScrollOffset + scrollAmount));
        } else {
            // 详情列表滚动
            detailScrollOffset = Math.max(0, Math.min(detailMaxScroll, detailScrollOffset + scrollAmount));
        }
        return true;
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 多文件播放逻辑：
     * 1. 收集选中 StagePack 中的所有 VMD 文件
     * 2. 找出相机 VMD（第一个 hasCamera=true 的文件）
     * 3. 加载所有非相机 VMD 并合并为一个动画
     * 4. 将合并后的动画 TransitionLayerTo 到模型 layer 0
     * 5. 加载相机 VMD 到 MMDCameraController
     */
    private void startStage() {
        StagePack pack = getSelectedPack();
        if (pack == null || !pack.hasMotionVmd()) return;
        
        NativeFunc nf = NativeFunc.GetInst();
        Minecraft mc = Minecraft.getInstance();
        
        // 保存配置
        StageConfig config = StageConfig.getInstance();
        config.lastStagePack = pack.getName();
        config.cinematicMode = cinematicMode;
        config.save();

        // 分离相机和动作 VMD
        StagePack.VmdFileInfo cameraFile = null;
        List<StagePack.VmdFileInfo> motionFiles = new ArrayList<>();
        
        for (StagePack.VmdFileInfo info : pack.getVmdFiles()) {
            if (info.hasCamera && cameraFile == null) {
                cameraFile = info;
            }
            if (info.hasBones || info.hasMorphs) {
                motionFiles.add(info);
            }
        }
        
        if (motionFiles.isEmpty()) {
            logger.warn("[舞台模式] 没有可用的动作 VMD");
            return;
        }

        // 加载第一个动作 VMD 作为合并目标
        long mergedAnim = nf.LoadAnimation(0, motionFiles.get(0).path);
        if (mergedAnim == 0) {
            logger.error("[舞台模式] 动作 VMD 加载失败: {}", motionFiles.get(0).path);
            return;
        }

        // 合并其余动作 VMD
        List<Long> tempHandles = new ArrayList<>();
        for (int i = 1; i < motionFiles.size(); i++) {
            long tempAnim = nf.LoadAnimation(0, motionFiles.get(i).path);
            if (tempAnim != 0) {
                nf.MergeAnimation(mergedAnim, tempAnim);
                tempHandles.add(tempAnim);
            }
        }
        
        // 释放临时句柄
        for (long handle : tempHandles) {
            nf.DeleteAnimation(handle);
        }
        
        // 加载相机 VMD
        long cameraAnim = 0;
        if (cameraFile != null) {
            if (cameraFile.hasBones || cameraFile.hasMorphs) {
                // 相机文件也含动作数据，单独加载相机
                cameraAnim = nf.LoadAnimation(0, cameraFile.path);
            } else {
                cameraAnim = nf.LoadAnimation(0, cameraFile.path);
            }
        }
        
        // 获取当前玩家模型句柄
        long modelHandle = 0;
        String modelName = null;
        if (mc.player != null) {
            String playerName = mc.player.getName().getString();
            modelName = ModelSelectorConfig.getInstance().getSelectedModel();
            if (modelName != null && !modelName.isEmpty()) {
                MMDModelManager.Model modelData = MMDModelManager.GetModel(modelName, playerName);
                if (modelData != null) {
                    modelHandle = modelData.model.GetModelLong();
                    nf.TransitionLayerTo(modelHandle, 0, mergedAnim, 0.3f);
                }
            }
        }

        // 获取音频路径（取第一个音频文件）
        String audioPath = pack.getFirstAudioPath();
        
        // 启动相机控制器（传递 modelHandle + modelName + 音频路径）
        MMDCameraController.getInstance().startStage(mergedAnim, cameraAnim, cinematicMode, modelHandle, modelName, audioPath);
        
        // 标记已启动（onClose 不会退出舞台模式）
        this.stageStarted = true;
        this.onClose();

        logger.info("[舞台模式] 开始: 包={}, 动作文件={}, 相机={}, 影院={}",
                   pack.getName(), motionFiles.size(), cameraFile != null, cinematicMode);
    }

    @Override
    public void onClose() {
        // 未启动播放时退出舞台模式（恢复视角）
        if (!stageStarted) {
            MMDCameraController.getInstance().exitStageMode();
        }
        super.onClose();
    }
    
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 2) + ".." : s;
    }
}
