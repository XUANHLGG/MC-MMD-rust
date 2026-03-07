package com.shiroha.mmdskin.ui.stage;

import com.shiroha.mmdskin.config.StagePack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 多人舞台交互面板。
 * 房主：邀请玩家 + 动作分配。
 * 成员：查看房间成员与准备状态。
 */
public class StageAssignPanel {

    private static final int PANEL_WIDTH = 180;
    private static final int BG = 0xC0101418;
    private static final int BORDER = 0xFF2A3A4A;
    private static final int ACCENT = 0xFF60A0D0;
    private static final int TEXT = 0xFFDDDDDD;
    private static final int TEXT_DIM = 0xFF888888;
    private static final int HOVER = 0x30FFFFFF;
    private static final int SELECTED = 0x3060A0D0;
    private static final int ASSIGNED = 0xFF40C080;
    private static final int UNASSIGNED = 0xFFD0A050;
    private static final int CHECKBOX_ON = 0xFF40C080;
    private static final int CHECKBOX_OFF = 0xFF505560;
    private static final int HEADER_HEIGHT = 20;
    private static final int ITEM_HEIGHT = 16;
    private static final int MARGIN = 4;
    private static final int STATE_PENDING_COLOR = 0xFFD0A050;
    private static final int STATE_DECLINED_COLOR = 0xFFD05050;
    private static final int STATE_BUSY_COLOR = 0xFFB06060;

    private final Font font;
    private int panelX, panelY, panelH;

    private List<StageInviteManager.HostEntry> hostEntries = new ArrayList<>();
    private List<StageInviteManager.MemberView> guestMembers = new ArrayList<>();
    private UUID selectedMemberUUID = null;
    private int hoveredMemberIndex = -1;

    private List<StagePack.VmdFileInfo> motionVmdFiles = new ArrayList<>();
    private int hoveredVmdIndex = -1;

    private int memberListTop, memberListBottom;
    private int assignTop, assignBottom;
    private int splitY;

    private int memberScrollOffset = 0;
    private int memberMaxScroll = 0;
    private int assignScrollOffset = 0;
    private int assignMaxScroll = 0;

    private boolean hoverInviteBtn = false;
    private int inviteBtnX, inviteBtnY;
    private static final int INVITE_BTN_W = 32;
    private static final int INVITE_BTN_H = 14;

    public StageAssignPanel(Font font) {
        this.font = font;
    }

    public void layout(int screenWidth, int screenHeight) {
        this.panelX = screenWidth - PANEL_WIDTH - MARGIN;
        this.panelY = MARGIN;
        this.panelH = screenHeight - MARGIN * 2;

        memberListTop = panelY + HEADER_HEIGHT;
        splitY = panelY + (int) ((panelH - HEADER_HEIGHT) * 0.45f) + HEADER_HEIGHT;
        memberListBottom = splitY - 2;

        assignTop = splitY + HEADER_HEIGHT;
        assignBottom = panelY + panelH - MARGIN;

        inviteBtnX = panelX + PANEL_WIDTH - INVITE_BTN_W - 6;
        inviteBtnY = panelY + 3;

        refreshPlayers();
        updateAssignScroll();
    }

    public void setStagePack(StagePack pack) {
        motionVmdFiles = new ArrayList<>();
        if (pack != null) {
            for (StagePack.VmdFileInfo info : pack.getVmdFiles()) {
                if (info.hasBones || info.hasMorphs) {
                    motionVmdFiles.add(info);
                }
            }
        }
        assignScrollOffset = 0;
        updateAssignScroll();
    }

    public void refreshPlayers() {
        StageInviteManager mgr = StageInviteManager.getInstance();
        if (mgr.isSessionMember()) {
            guestMembers = mgr.getSessionMembersView();
        } else {
            hostEntries = mgr.getHostPanelEntries();
            if (selectedMemberUUID != null) {
                boolean exists = hostEntries.stream().anyMatch(entry -> entry.uuid().equals(selectedMemberUUID));
                if (!exists) {
                    selectedMemberUUID = null;
                }
            }
        }
        updateMemberScroll();
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelH, BG);
        g.fill(panelX, panelY, panelX + 1, panelY + panelH, BORDER);

        renderHeader(g, mouseX, mouseY);
        renderMemberList(g, mouseX, mouseY);
        renderSeparator(g);
        renderLowerArea(g, mouseX, mouseY);
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        StageInviteManager mgr = StageInviteManager.getInstance();
        Component title = mgr.isSessionMember()
                ? Component.translatable("gui.mmdskin.stage.session_members")
                : Component.translatable("gui.mmdskin.stage.assign_title");
        g.drawCenteredString(font, title, panelX + PANEL_WIDTH / 2, panelY + 6, ACCENT);

        hoverInviteBtn = !mgr.isSessionMember()
                && mouseX >= inviteBtnX && mouseX <= inviteBtnX + INVITE_BTN_W
                && mouseY >= inviteBtnY && mouseY <= inviteBtnY + INVITE_BTN_H;
        if (!mgr.isSessionMember()) {
            int btnColor = hoverInviteBtn ? HOVER : 0x20FFFFFF;
            g.fill(inviteBtnX, inviteBtnY, inviteBtnX + INVITE_BTN_W, inviteBtnY + INVITE_BTN_H, btnColor);
            g.drawCenteredString(font, "+", inviteBtnX + INVITE_BTN_W / 2, inviteBtnY + 3, ACCENT);
        }
    }

    private void renderMemberList(GuiGraphics g, int mouseX, int mouseY) {
        hoveredMemberIndex = -1;
        g.enableScissor(panelX, memberListTop, panelX + PANEL_WIDTH, memberListBottom);

        StageInviteManager mgr = StageInviteManager.getInstance();
        if (mgr.isSessionMember()) {
            renderGuestMembers(g, mouseX, mouseY);
        } else {
            renderHostMembers(g, mouseX, mouseY);
        }

        g.disableScissor();
        renderScrollbar(g, memberListTop, memberListBottom, memberScrollOffset, memberMaxScroll);
    }

    private void renderHostMembers(GuiGraphics g, int mouseX, int mouseY) {
        if (hostEntries.isEmpty()) {
            g.drawCenteredString(font,
                    Component.translatable("gui.mmdskin.stage.no_nearby"),
                    panelX + PANEL_WIDTH / 2, memberListTop + 4, TEXT_DIM);
            return;
        }

        StageMotionAssignment assignment = StageMotionAssignment.getInstance();
        for (int i = 0; i < hostEntries.size(); i++) {
            StageInviteManager.HostEntry entry = hostEntries.get(i);
            int itemY = memberListTop + i * ITEM_HEIGHT - memberScrollOffset;
            if (itemY + ITEM_HEIGHT < memberListTop || itemY > memberListBottom) {
                continue;
            }

            int itemX = panelX + 4;
            int itemW = PANEL_WIDTH - 8;
            boolean hovered = mouseX >= itemX && mouseX <= itemX + itemW
                    && mouseY >= Math.max(itemY, memberListTop)
                    && mouseY <= Math.min(itemY + ITEM_HEIGHT, memberListBottom);
            if (hovered) {
                hoveredMemberIndex = i;
            }

            if (entry.uuid().equals(selectedMemberUUID)) {
                g.fill(itemX, itemY, itemX + itemW, itemY + ITEM_HEIGHT, SELECTED);
            } else if (hovered) {
                g.fill(itemX, itemY, itemX + itemW, itemY + ITEM_HEIGHT, HOVER);
            }

            String name = truncate(entry.name(), 11);
            int nameColor = entry.nearby() ? TEXT : TEXT_DIM;
            g.drawString(font, name, itemX + 2, itemY + 4, nameColor, false);

            int tagX = itemX + itemW;
            if (assignment.hasAssignment(entry.uuid())) {
                tagX -= font.width("♪") + 2;
                g.drawString(font, "♪", tagX, itemY + 4, ASSIGNED, false);
            }
            renderMemberState(g, tagX, itemY + 4, entry.state(), entry.useHostCamera());
        }
    }

    private void renderGuestMembers(GuiGraphics g, int mouseX, int mouseY) {
        if (guestMembers.isEmpty()) {
            g.drawCenteredString(font,
                    Component.translatable("gui.mmdskin.stage.waiting_host"),
                    panelX + PANEL_WIDTH / 2, memberListTop + 4, TEXT_DIM);
            return;
        }

        for (int i = 0; i < guestMembers.size(); i++) {
            StageInviteManager.MemberView member = guestMembers.get(i);
            int itemY = memberListTop + i * ITEM_HEIGHT - memberScrollOffset;
            if (itemY + ITEM_HEIGHT < memberListTop || itemY > memberListBottom) {
                continue;
            }

            int itemX = panelX + 4;
            int itemW = PANEL_WIDTH - 8;
            boolean hovered = mouseX >= itemX && mouseX <= itemX + itemW
                    && mouseY >= Math.max(itemY, memberListTop)
                    && mouseY <= Math.min(itemY + ITEM_HEIGHT, memberListBottom);
            if (hovered) {
                hoveredMemberIndex = i;
                g.fill(itemX, itemY, itemX + itemW, itemY + ITEM_HEIGHT, HOVER);
            }

            String prefix = member.local() ? "*" : member.host() ? "H" : " ";
            String name = truncate(prefix + " " + member.name(), 12);
            g.drawString(font, name, itemX + 2, itemY + 4, TEXT, false);
            renderMemberState(g, itemX + itemW, itemY + 4, member.state(), member.useHostCamera());
        }
    }

    private void renderMemberState(GuiGraphics g, int rightX, int y,
                                   StageInviteManager.MemberState state, boolean useHostCamera) {
        String tag;
        int color;
        switch (state) {
            case HOST -> {
                tag = "H";
                color = ACCENT;
            }
            case INVITED -> {
                tag = "...";
                color = STATE_PENDING_COLOR;
            }
            case ACCEPTED -> {
                tag = useHostCamera ? "C" : "✓";
                color = ASSIGNED;
            }
            case READY -> {
                tag = useHostCamera ? "★C" : "★";
                color = CHECKBOX_ON;
            }
            case DECLINED -> {
                tag = "✗";
                color = STATE_DECLINED_COLOR;
            }
            case BUSY -> {
                tag = "!";
                color = STATE_BUSY_COLOR;
            }
            default -> {
                tag = "+";
                color = UNASSIGNED;
            }
        }
        int width = font.width(tag);
        g.drawString(font, tag, rightX - width - 2, y, color, false);
    }

    private void renderSeparator(GuiGraphics g) {
        g.fill(panelX + 8, splitY - 1, panelX + PANEL_WIDTH - 8, splitY, 0x30FFFFFF);
    }

    private void renderLowerArea(GuiGraphics g, int mouseX, int mouseY) {
        if (StageInviteManager.getInstance().isSessionMember()) {
            renderGuestInfo(g);
        } else {
            renderAssignArea(g, mouseX, mouseY);
        }
    }

    private void renderGuestInfo(GuiGraphics g) {
        StageInviteManager mgr = StageInviteManager.getInstance();
        List<StageInviteManager.MemberView> members = mgr.getSessionMembersView();

        StageInviteManager.MemberView host = members.stream().filter(StageInviteManager.MemberView::host).findFirst().orElse(null);
        int totalMembers = Math.max(0, members.size() - 1);
        int readyMembers = 0;
        for (StageInviteManager.MemberView member : members) {
            if (!member.host() && member.state() == StageInviteManager.MemberState.READY) {
                readyMembers++;
            }
        }

        int y = splitY + 8;
        g.drawString(font,
                Component.translatable("gui.mmdskin.stage.host_label",
                        host != null ? host.name() : "-").getString(),
                panelX + 6, y, TEXT, false);
        y += 18;
        g.drawString(font,
                Component.translatable("gui.mmdskin.stage.ready_summary", readyMembers, totalMembers).getString(),
                panelX + 6, y, TEXT_DIM, false);
        y += 18;
        g.drawString(font,
                Component.translatable("gui.mmdskin.stage.camera_pref",
                        Component.translatable(mgr.isUseHostCamera() ? "gui.mmdskin.stage.on" : "gui.mmdskin.stage.off")).getString(),
                panelX + 6, y, TEXT_DIM, false);
        y += 18;
        g.drawString(font,
                Component.translatable(mgr.isLocalReady() ? "gui.mmdskin.stage.ready_done" : "gui.mmdskin.stage.waiting_host"),
                panelX + 6, y, mgr.isLocalReady() ? CHECKBOX_ON : TEXT_DIM, false);
    }

    private void renderAssignArea(GuiGraphics g, int mouseX, int mouseY) {
        hoveredVmdIndex = -1;

        UUID memberUUID = getSelectedAssignableMemberUUID();
        if (memberUUID == null) {
            g.drawCenteredString(font,
                    Component.translatable("gui.mmdskin.stage.select_member"),
                    panelX + PANEL_WIDTH / 2, splitY + 6, TEXT_DIM);
            return;
        }

        String memberName = hostEntries.stream()
                .filter(entry -> entry.uuid().equals(memberUUID))
                .map(StageInviteManager.HostEntry::name)
                .findFirst()
                .orElse(memberUUID.toString().substring(0, 8));

        String label = Component.translatable("gui.mmdskin.stage.assign_for", memberName).getString();
        g.drawString(font, truncate(label, 24), panelX + 6, splitY + 4, TEXT, false);

        g.enableScissor(panelX, assignTop, panelX + PANEL_WIDTH, assignBottom);

        List<String> assigned = StageMotionAssignment.getInstance().getAssignment(memberUUID);
        for (int i = 0; i < motionVmdFiles.size(); i++) {
            StagePack.VmdFileInfo info = motionVmdFiles.get(i);
            int itemY = assignTop + i * ITEM_HEIGHT - assignScrollOffset;
            if (itemY + ITEM_HEIGHT < assignTop || itemY > assignBottom) {
                continue;
            }

            int itemX = panelX + 4;
            int itemW = PANEL_WIDTH - 8;
            boolean hovered = mouseX >= itemX && mouseX <= itemX + itemW
                    && mouseY >= Math.max(itemY, assignTop)
                    && mouseY <= Math.min(itemY + ITEM_HEIGHT, assignBottom);
            if (hovered) {
                hoveredVmdIndex = i;
                g.fill(itemX, itemY, itemX + itemW, itemY + ITEM_HEIGHT, HOVER);
            }

            boolean checked = assigned.contains(info.name);
            int cbX = itemX + 2;
            int cbY = itemY + 3;
            int cbSize = 8;
            g.fill(cbX, cbY, cbX + cbSize, cbY + cbSize, checked ? CHECKBOX_ON : CHECKBOX_OFF);
            if (checked) {
                g.drawString(font, "✓", cbX + 1, cbY, 0xFFFFFFFF, false);
            }

            String fileName = info.name.toLowerCase().endsWith(".vmd")
                    ? info.name.substring(0, info.name.length() - 4)
                    : info.name;
            g.drawString(font, truncate(fileName, 14), cbX + cbSize + 4, itemY + 4, TEXT, false);

            String typeTag = info.getTypeTag();
            int tagW = font.width(typeTag);
            g.drawString(font, typeTag, itemX + itemW - tagW - 2, itemY + 4, TEXT_DIM, false);
        }

        g.disableScissor();
        renderScrollbar(g, assignTop, assignBottom, assignScrollOffset, assignMaxScroll);
    }

    private void renderScrollbar(GuiGraphics g, int top, int bottom, int offset, int maxScroll) {
        if (maxScroll <= 0) {
            return;
        }
        int barX = panelX + PANEL_WIDTH - 4;
        int barH = bottom - top;
        g.fill(barX, top, barX + 2, bottom, 0x20FFFFFF);
        int thumbH = Math.max(10, barH * barH / (barH + maxScroll));
        int thumbY = top + (int) ((barH - thumbH) * ((float) offset / maxScroll));
        g.fill(barX, thumbY, barX + 2, thumbY + thumbH, ACCENT);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }

        StageInviteManager mgr = StageInviteManager.getInstance();
        if (!mgr.isSessionMember() && hoverInviteBtn) {
            inviteAllNone();
            return true;
        }

        if (hoveredMemberIndex >= 0) {
            if (mgr.isSessionMember()) {
                return false;
            }

            if (hoveredMemberIndex < hostEntries.size()) {
                StageInviteManager.HostEntry entry = hostEntries.get(hoveredMemberIndex);
                switch (entry.state()) {
                    case NONE, DECLINED, BUSY -> {
                        mgr.sendInvite(entry.uuid());
                        return true;
                    }
                    case INVITED -> {
                        mgr.cancelInvite(entry.uuid());
                        if (entry.uuid().equals(selectedMemberUUID)) {
                            selectedMemberUUID = null;
                        }
                        return true;
                    }
                    case ACCEPTED, READY -> {
                        selectedMemberUUID = entry.uuid();
                        assignScrollOffset = 0;
                        updateAssignScroll();
                        return true;
                    }
                    default -> {
                        return false;
                    }
                }
            }
        }

        if (hoveredVmdIndex >= 0 && hoveredVmdIndex < motionVmdFiles.size()) {
            UUID memberUUID = getSelectedAssignableMemberUUID();
            if (memberUUID == null) {
                return false;
            }

            StagePack.VmdFileInfo info = motionVmdFiles.get(hoveredVmdIndex);
            StageMotionAssignment assignment = StageMotionAssignment.getInstance();
            if (assignment.getAssignment(memberUUID).contains(info.name)) {
                assignment.removeSingleVmd(memberUUID, info.name);
            } else {
                assignment.assignSingle(memberUUID, info.name);
            }
            return true;
        }

        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!isInside(mouseX, mouseY)) {
            return false;
        }

        int scrollAmount = (int) (-delta * ITEM_HEIGHT * 3);
        if (mouseY < splitY) {
            memberScrollOffset = Math.max(0, Math.min(memberMaxScroll, memberScrollOffset + scrollAmount));
        } else if (!StageInviteManager.getInstance().isSessionMember()) {
            assignScrollOffset = Math.max(0, Math.min(assignMaxScroll, assignScrollOffset + scrollAmount));
        }
        return true;
    }

    public boolean isInside(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX <= panelX + PANEL_WIDTH
                && mouseY >= panelY && mouseY <= panelY + panelH;
    }

    private UUID getSelectedAssignableMemberUUID() {
        if (selectedMemberUUID == null) {
            return null;
        }
        return hostEntries.stream()
                .filter(entry -> entry.uuid().equals(selectedMemberUUID))
                .filter(entry -> entry.state() == StageInviteManager.MemberState.ACCEPTED
                        || entry.state() == StageInviteManager.MemberState.READY)
                .map(StageInviteManager.HostEntry::uuid)
                .findFirst()
                .orElse(null);
    }

    private void inviteAllNone() {
        StageInviteManager mgr = StageInviteManager.getInstance();
        for (StageInviteManager.HostEntry entry : hostEntries) {
            if (entry.state() == StageInviteManager.MemberState.NONE && entry.nearby()) {
                mgr.sendInvite(entry.uuid());
            }
        }
    }

    private void updateMemberScroll() {
        int size = StageInviteManager.getInstance().isSessionMember() ? guestMembers.size() : hostEntries.size();
        int contentH = size * ITEM_HEIGHT;
        int visibleH = memberListBottom - memberListTop;
        memberMaxScroll = Math.max(0, contentH - visibleH);
        memberScrollOffset = Math.max(0, Math.min(memberMaxScroll, memberScrollOffset));
    }

    private void updateAssignScroll() {
        int contentH = motionVmdFiles.size() * ITEM_HEIGHT;
        int visibleH = assignBottom - assignTop;
        assignMaxScroll = Math.max(0, contentH - visibleH);
        assignScrollOffset = Math.max(0, Math.min(assignMaxScroll, assignScrollOffset));
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + ".." : s;
    }
}
