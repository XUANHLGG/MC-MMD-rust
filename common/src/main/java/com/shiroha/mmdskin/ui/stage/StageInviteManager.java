package com.shiroha.mmdskin.ui.stage;

import com.shiroha.mmdskin.ui.network.StageNetworkHandler;
import com.shiroha.mmdskin.ui.network.StageSessionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 多人舞台会话管理器（客户端单例）。
 *
 * 负责：
 * - 会话角色（房主/成员）与成员状态
 * - 邀请、接受、拒绝、取消与会话解散
 * - 互相邀请时的冲突仲裁
 * - 等待房主 / 观演中的成员状态
 */
public final class StageInviteManager {

    private static final StageInviteManager INSTANCE = new StageInviteManager();
    private static final double NEARBY_RANGE = 15.0;

    public enum LocalRole { NONE, HOST, MEMBER }

    public enum MemberState { NONE, HOST, INVITED, ACCEPTED, READY, DECLINED, BUSY }

    public record MemberView(UUID uuid, String name, MemberState state,
                             boolean local, boolean host, boolean useHostCamera) {
    }

    public record HostEntry(UUID uuid, String name, MemberState state,
                            boolean nearby, boolean useHostCamera) {
    }

    private static final class SessionMember {
        final UUID uuid;
        String name;
        MemberState state;
        boolean useHostCamera;
        boolean local;

        SessionMember(UUID uuid, String name, MemberState state, boolean useHostCamera, boolean local) {
            this.uuid = uuid;
            this.name = name;
            this.state = state;
            this.useHostCamera = useHostCamera;
            this.local = local;
        }
    }

    private static final class PendingInvite {
        final UUID hostUUID;
        final UUID sessionId;
        final String hostName;

        PendingInvite(UUID hostUUID, UUID sessionId, String hostName) {
            this.hostUUID = hostUUID;
            this.sessionId = sessionId;
            this.hostName = hostName;
        }
    }

    private final Map<UUID, SessionMember> sessionMembers = new LinkedHashMap<>();
    private final List<AbstractClientPlayer> nearbyPlayers = new ArrayList<>();

    private LocalRole localRole = LocalRole.NONE;
    private UUID sessionId = null;
    private UUID sessionHostUUID = null;
    private PendingInvite pendingInvite = null;

    private boolean localReady = false;
    private boolean useHostCamera = true;

    private boolean watchingStage = false;
    private String watchingStageData = null;

    private StageInviteManager() {
    }

    public static StageInviteManager getInstance() {
        return INSTANCE;
    }

    public synchronized LocalRole getLocalRole() {
        return localRole;
    }

    public synchronized boolean isSessionHost() {
        return localRole == LocalRole.HOST;
    }

    public synchronized boolean isSessionMember() {
        return localRole == LocalRole.MEMBER;
    }

    public synchronized MemberState getMemberState(UUID uuid) {
        SessionMember member = sessionMembers.get(uuid);
        return member != null ? member.state : MemberState.NONE;
    }

    public synchronized UUID getSessionId() {
        return sessionId;
    }

    public synchronized boolean hasPendingInvite() {
        return pendingInvite != null;
    }

    public synchronized boolean isUseHostCamera() {
        return useHostCamera;
    }

    public synchronized boolean isLocalReady() {
        return localReady;
    }

    public synchronized UUID getWatchingHostUUID() {
        return sessionHostUUID;
    }

    public synchronized String getWatchingStageData() {
        return watchingStageData;
    }

    public synchronized boolean matchesCurrentSession(UUID hostUUID, UUID incomingSessionId) {
        if (hostUUID == null || sessionHostUUID == null || !sessionHostUUID.equals(hostUUID)) {
            return false;
        }
        if (incomingSessionId == null) {
            return sessionId == null;
        }
        return incomingSessionId.equals(sessionId);
    }

    public synchronized boolean isWatchingStage() {
        return watchingStage;
    }

    public synchronized List<MemberView> getSessionMembersView() {
        List<MemberView> result = new ArrayList<>();
        for (SessionMember member : orderedMembers()) {
            result.add(new MemberView(
                    member.uuid,
                    member.name,
                    member.state,
                    member.local,
                    member.state == MemberState.HOST,
                    member.useHostCamera
            ));
        }
        return result;
    }

    public synchronized List<HostEntry> getHostPanelEntries() {
        refreshNearbyPlayers();

        LinkedHashMap<UUID, HostEntry> result = new LinkedHashMap<>();
        Set<UUID> nearbyIds = new HashSet<>();
        for (AbstractClientPlayer nearby : nearbyPlayers) {
            nearbyIds.add(nearby.getUUID());
        }

        for (SessionMember member : orderedMembers()) {
            if (member.local) {
                continue;
            }
            result.put(member.uuid, new HostEntry(
                    member.uuid,
                    member.name,
                    member.state,
                    nearbyIds.contains(member.uuid),
                    member.useHostCamera
            ));
        }

        for (AbstractClientPlayer nearby : nearbyPlayers) {
            result.putIfAbsent(nearby.getUUID(), new HostEntry(
                    nearby.getUUID(),
                    nearby.getName().getString(),
                    MemberState.NONE,
                    true,
                    false
            ));
        }

        return new ArrayList<>(result.values());
    }

    public synchronized List<AbstractClientPlayer> getNearbyPlayers() {
        refreshNearbyPlayers();
        return new ArrayList<>(nearbyPlayers);
    }

    public synchronized void setUseHostCamera(boolean use) {
        this.useHostCamera = use;
        updateLocalMemberState(localReady ? MemberState.READY : MemberState.ACCEPTED);
        if (localRole == LocalRole.MEMBER && sessionHostUUID != null && sessionId != null) {
            StageNetworkHandler.sendReady(sessionHostUUID, sessionId, localReady, useHostCamera);
        }
    }

    public synchronized void setLocalReady(boolean ready) {
        this.localReady = ready;
        updateLocalMemberState(ready ? MemberState.READY : MemberState.ACCEPTED);
        if (localRole == LocalRole.MEMBER && sessionHostUUID != null && sessionId != null) {
            StageNetworkHandler.sendReady(sessionHostUUID, sessionId, localReady, useHostCamera);
        }
    }

    public synchronized void sendInvite(UUID targetUUID) {
        UUID selfUUID = getLocalUUID();
        if (selfUUID == null || targetUUID == null || selfUUID.equals(targetUUID)) {
            return;
        }
        if (localRole == LocalRole.MEMBER) {
            return;
        }

        ensureHostedSession(selfUUID);

        SessionMember existing = sessionMembers.get(targetUUID);
        if (existing != null) {
            if (existing.state == MemberState.INVITED
                    || existing.state == MemberState.ACCEPTED
                    || existing.state == MemberState.READY) {
                return;
            }
        }

        sessionMembers.put(targetUUID, new SessionMember(
                targetUUID,
                resolvePlayerName(targetUUID),
                MemberState.INVITED,
                false,
                false
        ));
        StageNetworkHandler.sendStageInvite(targetUUID, sessionId);
    }

    public synchronized void cancelInvite(UUID targetUUID) {
        if (localRole != LocalRole.HOST || targetUUID == null || sessionId == null) {
            return;
        }

        SessionMember member = sessionMembers.get(targetUUID);
        if (member == null) {
            return;
        }

        if (member.state == MemberState.INVITED) {
            sessionMembers.remove(targetUUID);
            StageNetworkHandler.sendInviteCancel(targetUUID, sessionId);
        }
    }

    public synchronized void acceptInvite() {
        PendingInvite invite = pendingInvite;
        if (invite == null) {
            return;
        }

        pendingInvite = null;
        joinSessionAsMember(invite.hostUUID, invite.sessionId, invite.hostName);
        StageNetworkHandler.sendInviteResponse(invite.hostUUID, invite.sessionId, StageSessionMessage.Reply.ACCEPT);
    }

    public synchronized void declineInvite() {
        PendingInvite invite = pendingInvite;
        if (invite == null) {
            return;
        }

        pendingInvite = null;
        StageNetworkHandler.sendInviteResponse(invite.hostUUID, invite.sessionId, StageSessionMessage.Reply.DECLINE);
    }

    public synchronized void onInviteReceived(UUID hostUUID, UUID incomingSessionId) {
        UUID selfUUID = getLocalUUID();
        if (selfUUID == null || hostUUID == null || selfUUID.equals(hostUUID)) {
            return;
        }

        if (pendingInvite != null
                && pendingInvite.hostUUID.equals(hostUUID)
                && java.util.Objects.equals(pendingInvite.sessionId, incomingSessionId)) {
            return;
        }

        if (shouldYieldToRemoteHost(hostUUID)) {
            dissolveHostedSession(false);
            joinSessionAsMember(hostUUID, incomingSessionId, resolvePlayerName(hostUUID));
            pendingInvite = null;
            StageNetworkHandler.sendInviteResponse(hostUUID, incomingSessionId, StageSessionMessage.Reply.ACCEPT);
            return;
        }

        if (localRole == LocalRole.HOST) {
            SessionMember member = sessionMembers.get(hostUUID);
            if (member != null && member.state == MemberState.INVITED) {
                return;
            }
        }

        if (localRole != LocalRole.NONE) {
            StageNetworkHandler.sendInviteResponse(hostUUID, incomingSessionId, StageSessionMessage.Reply.BUSY);
            return;
        }

        if (pendingInvite != null
                && (!pendingInvite.hostUUID.equals(hostUUID) || !java.util.Objects.equals(pendingInvite.sessionId, incomingSessionId))) {
            StageNetworkHandler.sendInviteResponse(hostUUID, incomingSessionId, StageSessionMessage.Reply.BUSY);
            return;
        }

        pendingInvite = new PendingInvite(hostUUID, incomingSessionId, resolvePlayerName(hostUUID));
        StageInvitePopup.show(hostUUID);
    }

    public synchronized void onInviteCancelled(UUID hostUUID, UUID incomingSessionId) {
        if (pendingInvite != null
                && pendingInvite.hostUUID.equals(hostUUID)
                && java.util.Objects.equals(pendingInvite.sessionId, incomingSessionId)) {
            pendingInvite = null;
        }
    }

    public synchronized void onInviteReply(UUID memberUUID, UUID incomingSessionId, StageSessionMessage.Reply reply) {
        if (localRole != LocalRole.HOST || memberUUID == null || reply == null || !sessionMatches(incomingSessionId)) {
            return;
        }

        SessionMember member = sessionMembers.get(memberUUID);
        if (member == null) {
            return;
        }

        switch (reply) {
            case ACCEPT -> {
                member.state = MemberState.ACCEPTED;
                member.name = resolvePlayerName(memberUUID);
            }
            case DECLINE -> member.state = MemberState.DECLINED;
            case BUSY -> member.state = MemberState.BUSY;
        }

        broadcastSessionSnapshot();
    }

    public synchronized void onMemberReady(UUID memberUUID, UUID incomingSessionId,
                                           boolean ready, boolean memberUseHostCamera) {
        if (localRole != LocalRole.HOST || memberUUID == null || !sessionMatches(incomingSessionId)) {
            return;
        }

        SessionMember member = sessionMembers.get(memberUUID);
        if (member == null) {
            return;
        }

        member.useHostCamera = memberUseHostCamera;
        member.state = ready ? MemberState.READY : MemberState.ACCEPTED;
        broadcastSessionSnapshot();
    }

    public synchronized void onMemberLeft(UUID memberUUID, UUID incomingSessionId) {
        if (localRole != LocalRole.HOST || memberUUID == null || !sessionMatches(incomingSessionId)) {
            return;
        }

        sessionMembers.remove(memberUUID);
        StageMotionAssignment.getInstance().unassign(memberUUID);
        broadcastSessionSnapshot();
    }

    public synchronized void onSessionSnapshot(UUID hostUUID, UUID incomingSessionId,
                                               List<StageSessionMessage.MemberSnapshot> members) {
        if (hostUUID == null || incomingSessionId == null) {
            return;
        }
        if (localRole != LocalRole.MEMBER) {
            return;
        }
        if (!sessionMatches(incomingSessionId) || !hostUUID.equals(sessionHostUUID)) {
            return;
        }

        UUID selfUUID = getLocalUUID();
        pendingInvite = null;
        sessionHostUUID = hostUUID;
        sessionMembers.clear();
        if (members != null) {
            for (StageSessionMessage.MemberSnapshot snapshot : members) {
                UUID memberUUID = parseUUID(snapshot.uuid);
                if (memberUUID == null) {
                    continue;
                }

                MemberState state = parseMemberState(snapshot.state);
                boolean local = selfUUID != null && selfUUID.equals(memberUUID);
                SessionMember member = new SessionMember(
                        memberUUID,
                        snapshot.name != null && !snapshot.name.isEmpty() ? snapshot.name : resolvePlayerName(memberUUID),
                        state,
                        snapshot.useHostCamera,
                        local
                );
                sessionMembers.put(memberUUID, member);

                if (local) {
                    this.localReady = state == MemberState.READY;
                    this.useHostCamera = snapshot.useHostCamera;
                }
            }
        }

        if (selfUUID != null && !sessionMembers.containsKey(selfUUID)) {
            sessionMembers.put(selfUUID, new SessionMember(
                    selfUUID,
                    resolvePlayerName(selfUUID),
                    localReady ? MemberState.READY : MemberState.ACCEPTED,
                    useHostCamera,
                    true
            ));
        }
    }

    public synchronized void onSessionDissolved(UUID hostUUID, UUID incomingSessionId) {
        if (hostUUID == null) {
            return;
        }

        if (pendingInvite != null
                && pendingInvite.hostUUID.equals(hostUUID)
                && java.util.Objects.equals(pendingInvite.sessionId, incomingSessionId)) {
            pendingInvite = null;
        }

        if (localRole == LocalRole.MEMBER
                && hostUUID.equals(sessionHostUUID)
                && sessionMatches(incomingSessionId)) {
            clearSessionState(true);
        }
    }

    public synchronized Set<UUID> getAcceptedMembers() {
        Set<UUID> accepted = new HashSet<>();
        for (SessionMember member : sessionMembers.values()) {
            if (member.local) {
                continue;
            }
            if (member.state == MemberState.ACCEPTED || member.state == MemberState.READY) {
                accepted.add(member.uuid);
            }
        }
        return accepted;
    }

    public synchronized boolean allMembersReady() {
        boolean hasMembers = false;
        for (SessionMember member : sessionMembers.values()) {
            if (member.local) {
                continue;
            }
            if (member.state == MemberState.ACCEPTED || member.state == MemberState.READY) {
                hasMembers = true;
                if (member.state != MemberState.READY) {
                    return false;
                }
            }
        }
        return hasMembers;
    }

    public synchronized void onWatchStageStart(UUID hostUUID, String stageData) {
        this.watchingStage = true;
        this.sessionHostUUID = hostUUID;
        this.watchingStageData = stageData;
    }

    public synchronized void onWatchStageEnd(UUID hostUUID) {
        if (sessionHostUUID != null && sessionHostUUID.equals(hostUUID)) {
            stopWatchingStageOnly();
        }
    }

    public synchronized void stopWatching() {
        clearSessionState(true);
    }

    public synchronized void stopWatchingStageOnly() {
        this.watchingStage = false;
        this.watchingStageData = null;
    }

    public synchronized void notifyMembersStageEnd() {
        if (localRole != LocalRole.HOST || sessionId == null) {
            return;
        }
        for (SessionMember member : sessionMembers.values()) {
            if (member.local) {
                continue;
            }
            if (member.state == MemberState.ACCEPTED || member.state == MemberState.READY) {
                StageNetworkHandler.sendStageWatchEnd(member.uuid, sessionId);
            }
        }
        broadcastSessionSnapshot();
    }

    public synchronized void closeHostedSession() {
        if (localRole != LocalRole.HOST) {
            clearSessionState(true);
            return;
        }
        dissolveHostedSession(true);
    }

    public synchronized void resetHostState() {
        clearSessionState(true);
    }

    public synchronized void onDisconnect() {
        clearSessionState(true);
        pendingInvite = null;
        nearbyPlayers.clear();
    }

    private void ensureHostedSession(UUID selfUUID) {
        if (localRole == LocalRole.HOST && sessionId != null && sessionHostUUID != null) {
            ensureHostSelfEntry(selfUUID);
            return;
        }

        clearSessionState(false);
        pendingInvite = null;
        localRole = LocalRole.HOST;
        sessionId = UUID.randomUUID();
        sessionHostUUID = selfUUID;
        localReady = false;
        ensureHostSelfEntry(selfUUID);
    }

    private void ensureHostSelfEntry(UUID selfUUID) {
        sessionMembers.put(selfUUID, new SessionMember(
                selfUUID,
                resolvePlayerName(selfUUID),
                MemberState.HOST,
                false,
                true
        ));
    }

    private void joinSessionAsMember(UUID hostUUID, UUID incomingSessionId, String hostName) {
        clearSessionState(false);

        UUID selfUUID = getLocalUUID();
        localRole = LocalRole.MEMBER;
        sessionId = incomingSessionId;
        sessionHostUUID = hostUUID;
        localReady = false;
        watchingStage = false;
        watchingStageData = null;

        sessionMembers.put(hostUUID, new SessionMember(
                hostUUID,
                hostName != null && !hostName.isEmpty() ? hostName : resolvePlayerName(hostUUID),
                MemberState.HOST,
                false,
                false
        ));

        if (selfUUID != null) {
            sessionMembers.put(selfUUID, new SessionMember(
                    selfUUID,
                    resolvePlayerName(selfUUID),
                    MemberState.ACCEPTED,
                    useHostCamera,
                    true
            ));
        }
    }

    private void dissolveHostedSession(boolean notifyMembers) {
        UUID currentSessionId = sessionId;
        if (currentSessionId != null && notifyMembers) {
            for (SessionMember member : sessionMembers.values()) {
                if (member.local) {
                    continue;
                }
                StageNetworkHandler.sendSessionDissolve(member.uuid, currentSessionId);
            }
        }
        StageMotionAssignment.getInstance().reset();
        clearSessionState(true);
    }

    private void clearSessionState(boolean resetCameraPreference) {
        sessionMembers.clear();
        localRole = LocalRole.NONE;
        sessionId = null;
        sessionHostUUID = null;
        localReady = false;
        watchingStage = false;
        watchingStageData = null;
        if (resetCameraPreference) {
            useHostCamera = true;
        }
    }

    private boolean shouldYieldToRemoteHost(UUID remoteHostUUID) {
        UUID selfUUID = getLocalUUID();
        if (selfUUID == null || localRole != LocalRole.HOST || sessionId == null) {
            return false;
        }

        SessionMember remoteMember = sessionMembers.get(remoteHostUUID);
        if (remoteMember == null || remoteMember.state != MemberState.INVITED) {
            return false;
        }

        for (SessionMember member : sessionMembers.values()) {
            if (member.local || member.uuid.equals(remoteHostUUID)) {
                continue;
            }
            if (member.state == MemberState.ACCEPTED || member.state == MemberState.READY) {
                return false;
            }
        }

        return selfUUID.compareTo(remoteHostUUID) > 0;
    }

    private void broadcastSessionSnapshot() {
        if (localRole != LocalRole.HOST || sessionId == null) {
            return;
        }

        List<StageSessionMessage.MemberSnapshot> snapshots = buildSnapshots();
        for (SessionMember member : sessionMembers.values()) {
            if (member.local) {
                continue;
            }
            if (member.state == MemberState.ACCEPTED || member.state == MemberState.READY) {
                StageNetworkHandler.sendSessionSnapshot(member.uuid, sessionId, snapshots);
            }
        }
    }

    private List<StageSessionMessage.MemberSnapshot> buildSnapshots() {
        List<StageSessionMessage.MemberSnapshot> result = new ArrayList<>();
        for (SessionMember member : orderedMembers()) {
            result.add(new StageSessionMessage.MemberSnapshot(
                    member.uuid.toString(),
                    member.name,
                    member.state.name(),
                    member.useHostCamera
            ));
        }
        return result;
    }

    private List<SessionMember> orderedMembers() {
        List<SessionMember> result = new ArrayList<>(sessionMembers.values());
        result.sort(Comparator
                .comparingInt((SessionMember member) -> member.state == MemberState.HOST ? 0 : member.local ? 1 : 2)
                .thenComparing(member -> member.name == null ? "" : member.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private void updateLocalMemberState(MemberState state) {
        UUID selfUUID = getLocalUUID();
        if (selfUUID == null) {
            return;
        }
        SessionMember member = sessionMembers.get(selfUUID);
        if (member != null && member.state != MemberState.HOST) {
            member.state = state;
            member.useHostCamera = useHostCamera;
        }
    }

    private boolean sessionMatches(UUID incomingSessionId) {
        if (incomingSessionId == null) {
            return sessionId == null;
        }
        return sessionId != null && sessionId.equals(incomingSessionId);
    }

    private UUID getLocalUUID() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getUUID() : null;
    }

    private String resolvePlayerName(UUID uuid) {
        if (uuid == null) {
            return "Unknown";
        }

        SessionMember existing = sessionMembers.get(uuid);
        if (existing != null && existing.name != null && !existing.name.isEmpty()) {
            return existing.name;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Player player = mc.level.getPlayerByUUID(uuid);
            if (player != null) {
                return player.getName().getString();
            }
        }

        return uuid.toString().substring(0, 8);
    }

    private void refreshNearbyPlayers() {
        nearbyPlayers.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        UUID selfUUID = mc.player.getUUID();
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(selfUUID)) {
                continue;
            }
            if (mc.player.distanceTo(player) <= NEARBY_RANGE && player instanceof AbstractClientPlayer clientPlayer) {
                nearbyPlayers.add(clientPlayer);
            }
        }
        nearbyPlayers.sort(Comparator.comparing(player -> player.getName().getString(), String.CASE_INSENSITIVE_ORDER));
    }

    private static UUID parseUUID(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static MemberState parseMemberState(String raw) {
        if (raw == null || raw.isEmpty()) {
            return MemberState.NONE;
        }
        try {
            return MemberState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return MemberState.NONE;
        }
    }
}
