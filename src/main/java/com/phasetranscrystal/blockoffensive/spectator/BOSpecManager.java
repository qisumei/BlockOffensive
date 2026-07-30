package com.phasetranscrystal.blockoffensive.spectator;

import com.mojang.logging.LogUtils;
import com.phasetranscrystal.blockoffensive.BlockOffensive;
import com.phasetranscrystal.blockoffensive.entity.CompositionC4Entity;
import com.phasetranscrystal.blockoffensive.item.BOItemRegister;
import com.phasetranscrystal.blockoffensive.net.spec.KillCamS2CPacket;
import com.phasetranscrystal.blockoffensive.net.spec.RequestKillCamFallbackC2SPacket;
import com.phasetranscrystal.blockoffensive.net.spec.SwitchSpectateC2SPacket;
import com.phasetranscrystal.fpsmatch.FPSMatch;
import com.phasetranscrystal.fpsmatch.common.client.spec.SpectateMode;
import com.phasetranscrystal.fpsmatch.common.entity.MatchDropEntity;
import com.phasetranscrystal.fpsmatch.common.packet.spec.SpectateModeS2CPacket;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.map.BaseMap;
import com.phasetranscrystal.fpsmatch.core.team.MapTeams;
import com.phasetranscrystal.fpsmatch.core.team.ServerTeam;
import com.phasetranscrystal.fpsmatch.util.FPSMUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.phasetranscrystal.fpsmatch.util.FPSMFormatUtil.fmt2;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BOSpecManager {

    private static final Logger LOG = LogUtils.getLogger();

    private static final Map<UUID, SpectateMode> SPECTATE_MODE = new ConcurrentHashMap<>();
    private static final Map<UUID, KillCamDeathContext> DEATH_CONTEXTS = new ConcurrentHashMap<>();
    private static final long KILLCAM_CONTEXT_TTL_TICKS = 200L;
    private static final Map<UUID, Long> LAST_SENT_NS = new ConcurrentHashMap<>();
    private static final long DEDUP_NS = 250_000_000L; // 0.25s

    private static final Map<UUID, Long> FREE_MODE_EXPIRE_TICK = new ConcurrentHashMap<>();
    private static final long FREE_MODE_TIMEOUT_TICKS = 80L;

    private BOSpecManager() {
    }

    public static void sendKillCamAndAttach(ServerPlayer dead, DamageSource source) {
        ServerPlayer killer = FPSMUtil.getKiller(dead, source);
        if (killer == null) return;
        Vec3 kEye = killer.getEyePosition(1.0F);
        Vec3 dEye = DamagePosTracker.consumeVictimEye(dead).orElseGet(() -> dead.getEyePosition(1.0F));
        ItemStack weapon = FPSMUtil.getKillerWeapon(source);
        sendKillCamAndAttach(dead, killer, weapon, kEye, dEye);
    }

    public static void sendKillCamAndAttach(ServerPlayer dead, ServerPlayer killer, ItemStack weapon) {
        if (dead == null || killer == null) return;
        recordKillCamContext(dead, killer, FPSMCore.getInstance().getMapByPlayer(dead).orElse(null));
        Vec3 kEye = killer.getEyePosition(1.0F);
        Vec3 dEye = DamagePosTracker.consumeVictimEye(dead).orElseGet(() -> dead.getEyePosition(1.0F));
        sendKillCamAndAttach(dead, killer, weapon, kEye, dEye);
    }

    public static void recordKillCamContext(ServerPlayer dead, ServerPlayer killer, BaseMap map) {
        if (dead == null || killer == null || map == null) return;
        DEATH_CONTEXTS.put(dead.getUUID(), new KillCamDeathContext(
                killer.getUUID(), map.getGameType(), map.getMapName(), dead.serverLevel().getGameTime()));
    }

    public static Optional<ServerPlayer> getRecordedKiller(UUID victimId) {
        KillCamDeathContext context = victimId == null ? null : DEATH_CONTEXTS.get(victimId);
        if (context == null) return Optional.empty();
        ServerPlayer victim = FPSMCore.getInstance().getPlayerByUUID(victimId).orElse(null);
        if (victim == null || victim.serverLevel().getGameTime() - context.createdTick() > KILLCAM_CONTEXT_TTL_TICKS) {
            DEATH_CONTEXTS.remove(victimId);
            return Optional.empty();
        }
        return FPSMCore.getInstance().getPlayerByUUID(context.killerId());
    }

    public static boolean matchesRecordedMap(UUID victimId, BaseMap map) {
        KillCamDeathContext context = victimId == null ? null : DEATH_CONTEXTS.get(victimId);
        return context != null && map != null
                && context.gameType().equals(map.getGameType())
                && context.mapName().equals(map.getMapName());
    }

    public static void sendKillCamAndAttach(ServerPlayer dead, ServerPlayer killer,
                                            ItemStack weapon, Vec3 kEye, Vec3 dEye) {
        if (dead == null || killer == null) return;
        long now = System.nanoTime();
        Long prev = LAST_SENT_NS.get(dead.getUUID());
        if (prev != null && now - prev < DEDUP_NS) return;
        LAST_SENT_NS.put(dead.getUUID(), now);

        ItemStack weaponForSend = (weapon == null) ? ItemStack.EMPTY : weapon.copy();
        if (!weaponForSend.isEmpty() && weaponForSend.getCount() != 1) weaponForSend.setCount(1);

        LOG.info("[KillCamS] SEND packet to '{}'  killer='{}'  A(victimEye)=({},{},{})  B(killerEye)=({},{},{})  item='{}'",
                dead.getGameProfile().getName(), killer.getGameProfile().getName(),
                fmt2(dEye.x), fmt2(dEye.y), fmt2(dEye.z),
                fmt2(kEye.x), fmt2(kEye.y), fmt2(kEye.z),
                weaponForSend.isEmpty() ? "EMPTY" : weaponForSend.getHoverName().getString());

        FPSMatch.sendToPlayer(dead, new KillCamS2CPacket(
                killer.getUUID(), killer.getName().getString(), weaponForSend,
                kEye.x, kEye.y, kEye.z,
                dEye.x, dEye.y, dEye.z));

        DamagePosTracker.recordDeathPose(dead);
        SPECTATE_MODE.put(dead.getUUID(), SpectateMode.FREE);
        FREE_MODE_EXPIRE_TICK.put(dead.getUUID(), dead.serverLevel().getGameTime() + FREE_MODE_TIMEOUT_TICKS);
        FPSMatch.sendToPlayer(dead, new SpectateModeS2CPacket(SpectateMode.FREE));
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.side.isClient() || e.phase != TickEvent.Phase.END) return;
        if (!(e.player instanceof ServerPlayer sp)) return;

        UUID id = sp.getUUID();
        SpectateMode mode = SPECTATE_MODE.getOrDefault(id, SpectateMode.FREE);

        if (!sp.isSpectator()) {
            if (mode != SpectateMode.FREE) {
                SPECTATE_MODE.remove(id);
                FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> sp),
                        new SpectateModeS2CPacket(SpectateMode.FREE));
            }
            return;
        }

        if (mode == SpectateMode.FREE) {
            Long expireTick = FREE_MODE_EXPIRE_TICK.get(id);
            if (expireTick != null && sp.serverLevel().getGameTime() > expireTick) {
                selectAndApplyTarget(sp);
                FREE_MODE_EXPIRE_TICK.remove(id);
            }
            return;
        }

        if (mode == SpectateMode.ATTACH || mode == SpectateMode.TEAMMATE) {
            if (!isCameraOnTeammate(sp)) {
                selectAndApplyTarget(sp);
            }
        }
    }

    public static void requestAttachTeammate(ServerPlayer sp) {
        if (sp == null || !sp.isSpectator()) return;
        selectAndApplyTarget(sp);
    }

    public static void markSpecAttach(ServerPlayer sp) {
        if (sp == null || !sp.isSpectator()) return;
        markAttach(sp);
    }

    private static void markAttach(ServerPlayer sp) {
        SPECTATE_MODE.put(sp.getUUID(), SpectateMode.ATTACH);
        FREE_MODE_EXPIRE_TICK.remove(sp.getUUID());
        FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> sp),
                new SpectateModeS2CPacket(SpectateMode.ATTACH));
    }

    private static boolean isCameraOnTeammate(ServerPlayer sp) {
        Optional<BaseMap> mapOpt = FPSMCore.getInstance().getMapByPlayer(sp);
        if (mapOpt.isEmpty()) return false;
        MapTeams teams = mapOpt.get().getMapTeams();
        if (teams == null) return false;
        var myTeamOpt = teams.getTeamByPlayer(sp.getUUID());
        if (myTeamOpt.isEmpty()) return false;
        Entity cam = sp.getCamera();
        if (!(cam instanceof ServerPlayer cp)) return false;
        if (!cp.isAlive() || cp.isSpectator()) return false;
        var camTeamOpt = teams.getTeamByPlayer(cp.getUUID());
        return camTeamOpt.isPresent() && camTeamOpt.get() == myTeamOpt.get();
    }

    private static void selectAndApplyTarget(ServerPlayer spectator) {
        Optional<BaseMap> map = FPSMCore.getInstance().getMapByPlayer(spectator);
        if (map.isEmpty()) return;
        ServerTeam team = map.get().getMapTeams().getTeamByPlayer(spectator).orElse(null);
        if (team == null) return;

        List<ServerPlayer> teammates = team.getPlayerList().stream()
                .map(uuid -> spectator.server.getPlayerList().getPlayer(uuid))
                .filter(p -> p != null && p != spectator && p.isAlive() && !p.isSpectator())
                .sorted(Comparator.comparing(p -> p.getUUID().toString()))
                .toList();

        if (!teammates.isEmpty()) {
            spectator.setCamera(teammates.get(0));
            markAttach(spectator);
            return;
        }

        AABB bounds = map.get().mapArea.aabb();
        ServerLevel level = spectator.serverLevel();
        Entity c4 = findC4(level, bounds);
        if (c4 != null) {
            spectator.setCamera(c4);
            markAttach(spectator);
            return;
        }

        spectator.setCamera(spectator);
        markAttach(spectator);
    }

    private static Entity findC4(ServerLevel level, AABB bounds) {
        Entity placed = level.getEntitiesOfClass(CompositionC4Entity.class, bounds).stream()
                .filter(entity -> !entity.isRemoved()).findFirst().orElse(null);
        if (placed != null) return placed;
        Entity matchDrop = level.getEntitiesOfClass(MatchDropEntity.class, bounds).stream()
                .filter(entity -> entity.getItem().is(BOItemRegister.C4.get())).findFirst().orElse(null);
        if (matchDrop != null) return matchDrop;
        return level.getEntitiesOfClass(ItemEntity.class, bounds).stream()
                .filter(entity -> entity.getItem().is(BOItemRegister.C4.get())).findFirst().orElse(null);
    }

    @OnlyIn(Dist.CLIENT)
    public static void requestKillCamFallback(@NotNull UUID killer) {
        BlockOffensive.INSTANCE.sendToServer(new RequestKillCamFallbackC2SPacket(killer));
    }

    private record KillCamDeathContext(UUID killerId, String gameType, String mapName, long createdTick) {
    }

    @OnlyIn(Dist.CLIENT)
    public static void sendSwitchSpectate(SwitchSpectateC2SPacket.SwitchDirection dir) {
        BlockOffensive.INSTANCE.sendToServer(new SwitchSpectateC2SPacket(dir));
    }
}
