package com.phasetranscrystal.blockoffensive.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.phasetranscrystal.blockoffensive.BlockOffensive;
import com.phasetranscrystal.blockoffensive.data.DeathMessage;
import com.phasetranscrystal.blockoffensive.net.DeathMessageS2CPacket;
import com.phasetranscrystal.blockoffensive.sound.MVPMusicManager;
import com.phasetranscrystal.fpsmatch.common.capability.team.ShopCapability;
import com.phasetranscrystal.fpsmatch.common.command.FPSMHelpManager;
import com.phasetranscrystal.fpsmatch.common.command.FPSMCommandSuggests;
import com.phasetranscrystal.fpsmatch.common.command.FPSMCommandSuggests.FPSMSuggestionProvider;
import com.phasetranscrystal.fpsmatch.common.event.register.RegisterFPSMCommandEvent;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.map.BaseMap;
import com.phasetranscrystal.fpsmatch.core.shop.FPSMShop;
import com.phasetranscrystal.fpsmatch.core.shop.slot.ShopSlot;
import com.phasetranscrystal.fpsmatch.core.team.ServerTeam;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = BlockOffensive.MODID)
public class BOCommandRegister {

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        if (!FMLEnvironment.production) {
            BOTaczLiveFireDebugCommand.register(event.getDispatcher());
            BOPhysicsRagdollDebugCommand.register(event.getDispatcher());
            event.getDispatcher().register(Commands.literal("bo_debug_death_icons")
                    .executes(BOCommandRegister::handleDebugDeathIconsSelf)
                    .then(Commands.argument("targets", EntityArgument.players())
                            .executes(BOCommandRegister::handleDebugDeathIcons)));
        }
    }

    @SubscribeEvent
    public static void onFPSMCommandRegister(RegisterFPSMCommandEvent event) {
        event.addChild(Commands.literal("mvp")
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("sound", ResourceLocationArgument.id())
                                .suggests(SuggestionProviders.AVAILABLE_SOUNDS)
                                .executes(BOCommandRegister::handleMvp)
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(BOCommandRegister::handleMvpWithName)))));
        FPSMHelpManager.getInstance().registerCommandHelp("fpsm mvp", Component.translatable("commands.blockoffensive.mvp.description"));
        FPSMHelpManager.getInstance().registerCommandParameters("fpsm mvp", "*targets", "*sound", "[name]");

        SuggestionProvider<CommandSourceStack> csMapNames = new FPSMSuggestionProvider((c, b) -> FPSMCommandSuggests.getSuggestions(b, FPSMCore.getInstance().getMapNames("cs")));

        event.addChild(Commands.literal("clonedata")
                .then(Commands.literal("cs")
                        .then(Commands.argument(FPSMCommandSuggests.MAP_NAME_ARG, StringArgumentType.string())
                                .suggests(csMapNames)
                                .then(Commands.literal("shopdata")
                                        .then(Commands.argument("target_map", StringArgumentType.string())
                                                .suggests(csMapNames)
                                                .executes(BOCommandRegister::handleCloneShopData)))
                                .then(Commands.literal("gamedata")
                                        .then(Commands.argument("target_map", StringArgumentType.string())
                                                .suggests(csMapNames)
                                                .executes(BOCommandRegister::handleCloneGameData))))));

        FPSMHelpManager.getInstance().registerCommandHelp("fpsm clonedata cs *map_name shopdata",
                Component.translatable("commands.blockoffensive.clonedata.shopdata.help"));
        FPSMHelpManager.getInstance().registerCommandHelp("fpsm clonedata cs *map_name gamedata",
                Component.translatable("commands.blockoffensive.clonedata.gamedata.help"));

        if (!FMLEnvironment.production) {
            event.addChild(BOTaczLiveFireDebugCommand.fpsmCommand());
            event.addChild(BOPhysicsRagdollDebugCommand.fpsmCommand());
            event.addChild(Commands.literal("debug_death_icons")
                    .requires(source -> source.hasPermission(2))
                    .executes(BOCommandRegister::handleDebugDeathIconsSelf)
                    .then(Commands.argument("targets", EntityArgument.players())
                            .executes(BOCommandRegister::handleDebugDeathIcons)));
        }
    }

    private static int handleMvp(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");
        ResourceLocation sound = ResourceLocationArgument.getId(context, "sound");
        players.forEach(player -> MVPMusicManager.getInstance().addMvpMusic(player.getUUID().toString(), sound, sound.toString()));
        context.getSource().sendSuccess(() -> Component.translatable("commands.blockoffensive.mvp.success", players.size(), sound.toString()), true);
        return 1;
    }

    private static int handleMvpWithName(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");
        ResourceLocation sound = ResourceLocationArgument.getId(context, "sound");
        String name = StringArgumentType.getString(context, "name");
        players.forEach(player -> MVPMusicManager.getInstance().addMvpMusic(player.getUUID().toString(), sound, name));
        context.getSource().sendSuccess(() -> Component.translatable("commands.blockoffensive.mvp.success", players.size(), name), true);
        return 1;
    }

    private static int handleDebugDeathIconsSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return sendDebugDeathIcons(context, java.util.List.of(context.getSource().getPlayerOrException()));
    }

    private static int handleDebugDeathIcons(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return sendDebugDeathIcons(context, EntityArgument.getPlayers(context, "targets"));
    }

    private static int sendDebugDeathIcons(CommandContext<CommandSourceStack> context, Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            DeathMessage message = new DeathMessage.Builder(
                    Component.literal("DevKiller"),
                    player.getUUID(),
                    Component.literal("IconVictim"),
                    UUID.randomUUID(),
                    new ItemStack(Items.DIAMOND_SWORD)
            )
                    .setHeadShot(true)
                    .setThroughSmoke(true)
                    .setThroughWall(true)
                    .build();
            BlockOffensive.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new DeathMessageS2CPacket(message));
        }

        context.getSource().sendSuccess(() -> Component.literal("Sent debug death icon message to " + players.size() + " player(s)."), true);
        return players.size();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int handleCloneShopData(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String sourceMapName = StringArgumentType.getString(context, FPSMCommandSuggests.MAP_NAME_ARG);
        String targetMapName = StringArgumentType.getString(context, "target_map");

        BaseMap sourceMap = FPSMCore.getInstance().getMapByName(sourceMapName).orElse(null);
        BaseMap targetMap = FPSMCore.getInstance().getMapByName(targetMapName).orElse(null);

        if (sourceMap == null) {
            context.getSource().sendFailure(Component.literal("Source map '" + sourceMapName + "' not found."));
            return 0;
        }
        if (targetMap == null) {
            context.getSource().sendFailure(Component.literal("Target map '" + targetMapName + "' not found."));
            return 0;
        }

        int copied = 0;
        for (ServerTeam targetTeam : targetMap.getMapTeams().getNormalTeams()) {
            ShopCapability targetCap = targetTeam.getCapabilityMap().get(ShopCapability.class).orElse(null);
            if (targetCap == null || !targetCap.isInitialized()) continue;

            var sourceShopOpt = sourceMap.getMapTeams().getTeamByName(targetTeam.getName())
                    .flatMap(sourceTeam -> sourceTeam.getCapabilityMap().get(ShopCapability.class))
                    .flatMap(ShopCapability::getShopSafe);

            if (sourceShopOpt.isEmpty()) continue;

            FPSMShop sourceShop = (FPSMShop) (Object) sourceShopOpt.get();
            FPSMShop targetShop = (FPSMShop) (Object) targetCap.getShop();

            Map rawData = new HashMap();
            Map rawSourceData = sourceShop.getDefaultShopDataMapString();
            for (Object rawType : rawSourceData.keySet()) {
                String type = (String) rawType;
                List<ShopSlot> slots = (List<ShopSlot>) rawSourceData.get(rawType);
                ArrayList<ShopSlot> copies = new ArrayList<>();
                for (ShopSlot s : slots) {
                    copies.add(s.copy());
                }
                rawData.put(targetShop.valueOf(type), copies);
            }
            targetShop.setDefaultShopData(rawData);
            copied++;
        }

        if (copied == 0) {
            context.getSource().sendFailure(Component.literal("No initialized shop teams found in target map."));
            return 0;
        }

        FPSMCore.getInstance().getFPSMDataManager().saveData(targetMap, targetMap.getMapName(), true);
        int count = copied;
        context.getSource().sendSuccess(
                () -> Component.literal("Copied shop data from '" + sourceMapName
                        + "' to '" + targetMapName + "' (" + count + " teams)"), true);
        return 1;
    }

    private static int handleCloneGameData(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String sourceMapName = StringArgumentType.getString(context, FPSMCommandSuggests.MAP_NAME_ARG);
        String targetMapName = StringArgumentType.getString(context, "target_map");

        BaseMap sourceMap = FPSMCore.getInstance().getMapByName(sourceMapName).orElse(null);
        BaseMap targetMap = FPSMCore.getInstance().getMapByName(targetMapName).orElse(null);

        if (sourceMap == null) {
            context.getSource().sendFailure(Component.literal("Source map '" + sourceMapName + "' not found."));
            return 0;
        }
        if (targetMap == null) {
            context.getSource().sendFailure(Component.literal("Target map '" + targetMapName + "' not found."));
            return 0;
        }

        targetMap.configFromJson(sourceMap.configToJson());
        targetMap.saveConfig();
        FPSMCore.getInstance().getFPSMDataManager().saveData(targetMap, targetMap.getMapName(), true);

        context.getSource().sendSuccess(
                () -> Component.literal("Copied game data from '" + sourceMapName
                        + "' to '" + targetMapName + "'"), true);
        return 1;
    }

}
