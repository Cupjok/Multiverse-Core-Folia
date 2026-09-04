package org.mvplugins.multiverse.core.utils.compatibility;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import io.papermc.lib.PaperLib;
import io.vavr.control.Try;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import org.mvplugins.multiverse.core.utils.ReflectHelper;

/**
 * Compatibility class used to teleport an entity without blocking the calling thread.
 *
 * <p>A regionised server refuses the synchronous {@link Entity#teleport(Location)} outright, so
 * the asynchronous form is the only option there. PaperLib would normally paper over that
 * difference, but it picks its strategy from the server's version string and silently falls back
 * to the synchronous teleport on versions it does not recognise - which is exactly what happens
 * on current Minecraft builds. This calls the server's own {@code teleportAsync} directly when it
 * exists, and only defers to PaperLib on servers old enough to lack it.</p>
 *
 * @since 5.3
 */
@ApiStatus.AvailableSince("5.3")
public final class EntityTeleportCompatibility {

    private static final Try<Method> TELEPORT_ASYNC_METHOD =
            ReflectHelper.tryGetMethod(Entity.class, "teleportAsync", Location.class);

    /**
     * Checks whether the server exposes a native asynchronous teleport.
     *
     * @return True if {@code Entity#teleportAsync(Location)} is available.
     *
     * @since 5.3
     */
    @ApiStatus.AvailableSince("5.3")
    public static boolean isAsyncTeleportSupported() {
        return TELEPORT_ASYNC_METHOD.isSuccess();
    }

    /**
     * Teleports an entity asynchronously.
     *
     * @param entity    The entity to teleport.
     * @param location  Where to teleport it to.
     * @return A future completed with whether the teleport succeeded.
     *
     * @since 5.3
     */
    @ApiStatus.AvailableSince("5.3")
    @SuppressWarnings("unchecked")
    public static @NotNull CompletableFuture<Boolean> teleportAsync(
            @NotNull Entity entity, @NotNull Location location) {
        return TELEPORT_ASYNC_METHOD
                .flatMap(method -> ReflectHelper.<Entity, CompletableFuture<Boolean>>tryInvokeMethod(
                        entity, method, location))
                .getOrElseGet(throwable -> PaperLib.teleportAsync(entity, location));
    }

    private EntityTeleportCompatibility() {
        throw new IllegalStateException("Utility class");
    }
}
