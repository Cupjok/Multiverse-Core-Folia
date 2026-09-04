package org.mvplugins.multiverse.core.teleportation;

import io.vavr.control.Either;
import jakarta.inject.Inject;
import org.bukkit.Location;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jvnet.hk2.annotations.Service;

import org.mvplugins.multiverse.core.MultiverseCore;
import org.mvplugins.multiverse.core.destination.DestinationInstance;
import org.mvplugins.multiverse.core.utils.scheduler.MVScheduler;

/**
 * Teleports entities safely and asynchronously. Provider for the {@link AsyncSafetyTeleporter}.
 */
@Service
public final class AsyncSafetyTeleporter {
    @NotNull
    private final MultiverseCore multiverseCore;
    private final BlockSafety blockSafety;
    private final TeleportQueue teleportQueue;
    private final PluginManager pluginManager;
    private final MVScheduler scheduler;

    @Inject
    AsyncSafetyTeleporter(
            @NotNull MultiverseCore multiverseCore,
            @NotNull BlockSafety blockSafety,
            @NotNull TeleportQueue teleportQueue,
            @NotNull PluginManager pluginManager,
            @NotNull MVScheduler scheduler) {
        this.multiverseCore = multiverseCore;
        this.blockSafety = blockSafety;
        this.teleportQueue = teleportQueue;
        this.pluginManager = pluginManager;
        this.scheduler = scheduler;
    }

    /**
     * Sets the location to teleport to.
     *
     * @param location The location
     * @return A new {@link AsyncSafetyTeleporterAction} to be chained
     */
    public AsyncSafetyTeleporterAction to(@Nullable Location location) {
        return new AsyncSafetyTeleporterAction(
                multiverseCore,
                blockSafety,
                teleportQueue,
                pluginManager,
                scheduler,
                Either.left(location)
        );
    }

    /**
     * Sets the destination to teleport to.
     *
     * @param destination The destination
     * @return A new {@link AsyncSafetyTeleporterAction} to be chained
     */
    public AsyncSafetyTeleporterAction to(@Nullable DestinationInstance<?, ?> destination) {
        return new AsyncSafetyTeleporterAction(
                multiverseCore,
                blockSafety,
                teleportQueue,
                pluginManager,
                scheduler,
                Either.right(destination)
        );
    }
}
