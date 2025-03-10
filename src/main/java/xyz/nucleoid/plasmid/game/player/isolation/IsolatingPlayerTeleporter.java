package xyz.nucleoid.plasmid.game.player.isolation;

import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.biome.source.BiomeAccess;
import xyz.nucleoid.plasmid.game.GameSpace;

import java.util.function.Function;

/**
 * Teleports payer in and out of a {@link GameSpace}. This involves ensuring that the player does not bring anything
 * into the game space as well as to not bring anything out of the game space.
 * <p>
 * The player's NBT must be saved on entry to a game space, and it must not be saved when exiting and instead restored.
 * <p>
 * This class is also responsible for resetting player state and sending packets such that the player is fully refreshed
 * after teleporting and no weird issues can arise from invalid state passing through dimensions.
 */
public final class IsolatingPlayerTeleporter {
    private final MinecraftServer server;

    public IsolatingPlayerTeleporter(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Teleports a player into a {@link GameSpace}. The player will save any associated data before teleporting.
     *
     * @param player the player to teleport
     * @param recreate a function describing how the new teleported player should be initialized
     */
    public void teleportIn(ServerPlayerEntity player, Function<ServerPlayerEntity, ServerWorld> recreate) {
        this.teleport(player, recreate, true);
    }

    /**
     * Teleports a player out of a {@link GameSpace}. The player will NOT save any associated data before teleporting,
     * and instead will restore any previously saved data.
     *
     * @param player the player to teleport
     * @param recreate a function describing how the new teleported player should be initialized
     */
    public void teleportOut(ServerPlayerEntity player, Function<ServerPlayerEntity, ServerWorld> recreate) {
        this.teleport(player, recreate, false);
    }

    /**
     * Teleports a player out of a {@link GameSpace} and into the passed world. The player will NOT save any associated
     * data before teleporting, and instead will restore any previously saved data.
     *
     * @param player the player to teleport
     * @param world the world to teleport to
     */
    public void teleportOutTo(ServerPlayerEntity player, ServerWorld world) {
        this.teleportOut(player, newPlayer -> world);
    }

    /**
     * Teleports a player out of a {@link GameSpace} and into the previous world that they were apart of. The player
     * will NOT save any associated data before teleporting, and instead will restore any previously saved data.
     *
     * @param player the player to teleport
     */
    public void teleportOut(ServerPlayerEntity player) {
        this.teleportOut(player, ServerPlayerEntity::getServerWorld);
    }

    private void teleport(ServerPlayerEntity player, Function<ServerPlayerEntity, ServerWorld> recreate, boolean in) {
        var playerManager = this.server.getPlayerManager();
        var playerManagerAccess = (PlayerManagerAccess) playerManager;

        player.detach();
        player.setCameraEntity(player);

        if (in) {
            playerManagerAccess.plasmid$savePlayerData(player);
        }

        player.getAdvancementTracker().clearCriteria();
        this.server.getBossBarManager().onPlayerDisconnect(player);

        player.getServerWorld().removePlayer(player, Entity.RemovalReason.CHANGED_DIMENSION);
        player.unsetRemoved();

        playerManagerAccess.plasmid$getPlayerResetter().apply(player);

        if (!in) {
            playerManagerAccess.plasmid$loadIntoPlayer(player);
        }

        var world = recreate.apply(player);
        player.setWorld(world);

        var worldProperties = world.getLevelProperties();

        var networkHandler = player.networkHandler;
        networkHandler.sendPacket(new PlayerRespawnS2CPacket(
                world.getDimension(), world.getRegistryKey(),
                BiomeAccess.hashSeed(world.getSeed()),
                player.interactionManager.getGameMode(), player.interactionManager.getPreviousGameMode(),
                world.isDebugWorld(), world.isFlat(), false
        ));

        networkHandler.sendPacket(new DifficultyS2CPacket(worldProperties.getDifficulty(), worldProperties.isDifficultyLocked()));
        networkHandler.sendPacket(new PlayerAbilitiesS2CPacket(player.getAbilities()));
        networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(player.getInventory().selectedSlot));

        player.closeHandledScreen();

        playerManager.sendCommandTree(player);
        player.getRecipeBook().sendInitRecipesPacket(player);

        world.onPlayerTeleport(player);
        networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());

        this.server.getBossBarManager().onPlayerConnect(player);

        playerManager.sendWorldInfo(player, world);
        playerManager.sendPlayerStatus(player);

        // we just sent the full inventory, so we can consider the ScreenHandler as up-to-date
        ((ScreenHandlerAccess) player.playerScreenHandler).plasmid$resetTrackedState();

        for (var effect : player.getStatusEffects()) {
            networkHandler.sendPacket(new EntityStatusEffectS2CPacket(player.getId(), effect));
        }
    }
}
