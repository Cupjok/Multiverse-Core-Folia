/******************************************************************************
 * Multiverse 2 Copyright (c) the Multiverse Team 2011.                       *
 * Multiverse 2 is licensed under the BSD License.                            *
 * For more information please check the README.md file included              *
 * with this project.                                                         *
 ******************************************************************************/

package org.mvplugins.multiverse.core.utils.scheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.inject.Inject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jvnet.hk2.annotations.Service;

import org.mvplugins.multiverse.core.MultiverseCore;

/**
 * Central scheduling facade that hides the difference between regionised servers (Folia and its
 * forks, such as Canvas) and traditional single-main-thread servers (Paper, Spigot).
 *
 * <p>On a regionised server there is no single "main thread". Work is instead owned by one of
 * several schedulers, and running a task on the wrong one either throws or silently corrupts
 * state:</p>
 *
 * <ul>
 *     <li><b>Global region</b> - server-wide state: world creation and unloading, the world list,
 *     and plugin configuration. See {@link #runGlobal(Runnable)}.</li>
 *     <li><b>Entity scheduler</b> - work that follows a specific entity as it moves between
 *     regions, such as post-teleport fix-ups. See {@link #runAtEntity(Entity, Runnable)}.</li>
 *     <li><b>Region scheduler</b> - work owned by the region covering a location.
 *     See {@link #runAtLocation(Location, Runnable)}.</li>
 *     <li><b>Async scheduler</b> - work that must not touch the server at all.
 *     See {@link #runAsync(Runnable)}.</li>
 * </ul>
 *
 * <p>On a non-regionised server every "sync" variant below falls back to the ordinary Bukkit
 * scheduler and the main thread, so call sites do not need to branch on the platform.</p>
 *
 * @since 5.3
 */
@Service
public final class MVScheduler {

    private static final long MILLIS_PER_TICK = 50L;

    private static final boolean FOLIA = detectFolia();

    private static volatile boolean serverStarted = false;

    private static volatile MVScheduler instance;

    private final MultiverseCore plugin;

    @Inject
    MVScheduler(@NotNull MultiverseCore plugin) {
        this.plugin = plugin;
        instance = this;
    }

    /**
     * Gets the active scheduler, for the few places that cannot receive it by injection.
     *
     * @return The scheduler, or null if Multiverse has not finished wiring itself up yet.
     */
    public static @Nullable MVScheduler get() {
        return instance;
    }

    /**
     * Runs an action against server-wide state through the active scheduler, falling back to
     * running it inline if Multiverse is not wired up yet.
     *
     * @param action    The action to run.
     */
    public static void onGlobalRegion(@NotNull Runnable action) {
        MVScheduler scheduler = instance;
        if (scheduler == null) {
            action.run();
            return;
        }
        scheduler.runGlobal(action);
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Whether this server regionises ticking, i.e. is Folia or a fork of it such as Canvas.
     *
     * @return True if the server is regionised.
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Arms the switch from startup behaviour to normal scheduling.
     *
     * <p>This queues a marker task on the same scheduler the rest of Multiverse will use for
     * server-wide work, so the switch happens exactly when that scheduler is proven to be
     * draining tasks. Until then everything runs inline on the starting server's single thread:
     * on a regionised server the global region scheduler does not drain until ticking begins, so
     * a task submitted during startup would never run and anything waiting on it would hang the
     * boot.</p>
     *
     * @param plugin    The plugin to own the marker task.
     */
    public static void armStartupCompletion(@NotNull Plugin plugin) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> serverStarted = true);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> serverStarted = true);
        }
    }

    /**
     * Whether the calling thread is the one that owns server-wide state.
     *
     * <p>On a regionised server this is the global region thread; elsewhere it is the main
     * server thread.</p>
     *
     * <p>Note that this is {@code false} on the thread that starts the server, even though some
     * server-wide operations are legal there. See {@link #isStartupPhase()}.</p>
     *
     * @return True if the current thread may safely touch server-wide state.
     */
    public boolean isGlobalThread() {
        return FOLIA ? Bukkit.isGlobalTickThread() : Bukkit.isPrimaryThread();
    }

    /**
     * Whether the calling thread is one of the server's ticking threads.
     *
     * <p>On a regionised server these threads come from a shared pool, so blocking one to wait on
     * another region can starve the pool and stall the server. Nothing here may ever wait on
     * another region's work.</p>
     *
     * @return True if the current thread is a server tick thread.
     */
    public boolean isTickThread() {
        return Bukkit.isPrimaryThread();
    }

    /**
     * Whether the server is still starting up and has not begun running scheduled tasks.
     *
     * <p>Regionised servers treat the starting server as a special case, and permit some
     * server-wide work - notably creating and loading worlds - from the thread that is enabling
     * plugins. Other work, such as changing a world's settings or reading its blocks, is still
     * rejected there and has to be deferred until ticking begins.</p>
     *
     * @return True if the server has not started running scheduled tasks yet.
     */
    public boolean isStartupPhase() {
        return !serverStarted;
    }

    /**
     * Computes a value on the thread that owns the region containing the given location, waiting
     * for the result.
     *
     * <p>Never call this from the global region thread or from the region that would have to run
     * the computation - it would wait on itself.</p>
     *
     * @param location  The location whose region owns the computation.
     * @param supplier  The computation to run.
     * @param <T>       The type of the computed value.
     * @return A future completed with the computed value.
     */
    public <T> @NotNull CompletableFuture<T> supplyAtLocation(
            @NotNull Location location, @NotNull Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runAtLocation(location, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    /**
     * Whether the calling thread may read blocks at the given location.
     *
     * @param location  The location to check.
     * @return True if block reads at that location are legal on this thread.
     */
    public boolean canReadAt(@NotNull Location location) {
        return !FOLIA || Bukkit.isOwnedByCurrentRegion(location);
    }

    /**
     * Whether the calling thread owns the region that the given entity currently occupies.
     *
     * @param entity    The entity to check ownership of.
     * @return True if the current thread may safely touch the entity.
     */
    public boolean isOwnedByCurrentThread(@NotNull Entity entity) {
        return FOLIA ? Bukkit.isOwnedByCurrentRegion(entity) : Bukkit.isPrimaryThread();
    }

    // ---------------------------------------------------------------------
    // Global region
    // ---------------------------------------------------------------------

    /**
     * Runs an action against server-wide state, immediately if the calling thread already owns
     * that state and on the global region otherwise.
     *
     * <p>Because the action may be deferred, callers must not assume it has finished when this
     * method returns. Use {@link #supplyGlobal(Supplier)} when the result is needed.</p>
     *
     * @param action    The action to run.
     */
    public void runGlobal(@NotNull Runnable action) {
        if (isGlobalThread()) {
            action.run();
            return;
        }
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, action);
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    /**
     * Runs an action against server-wide state after a delay.
     *
     * @param action        The action to run.
     * @param delayTicks    Ticks to wait before running, minimum one tick.
     * @return A handle that can cancel the pending action.
     */
    public @NotNull MVTask runGlobalLater(@NotNull Runnable action, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        return FOLIA
                ? MVTask.of(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> action.run(), delay))
                : MVTask.of(Bukkit.getScheduler().runTaskLater(plugin, action, delay));
    }

    /**
     * Computes a value against server-wide state, on the global region if the calling thread does
     * not already own it.
     *
     * <p>The returned future completes on whichever thread ran the supplier. Never block the
     * global region thread on the result of this method.</p>
     *
     * @param supplier  The computation to run.
     * @param <T>       The type of the computed value.
     * @return A future completed with the computed value, or completed exceptionally if the
     *         supplier threw.
     */
    public <T> @NotNull CompletableFuture<T> supplyGlobal(@NotNull Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runGlobal(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    // ---------------------------------------------------------------------
    // Entity and region
    // ---------------------------------------------------------------------

    /**
     * Runs an action on the thread that owns the given entity, immediately if that is already the
     * calling thread.
     *
     * <p>On a regionised server the action is silently dropped if the entity is removed before it
     * runs, which matches the behaviour Multiverse wants for post-teleport fix-ups.</p>
     *
     * @param entity    The entity the action operates on.
     * @param action    The action to run.
     */
    public void runAtEntity(@NotNull Entity entity, @NotNull Runnable action) {
        if (isOwnedByCurrentThread(entity)) {
            action.run();
            return;
        }
        if (FOLIA) {
            entity.getScheduler().execute(plugin, action, null, 1L);
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    /**
     * Runs an action on the thread that owns the given entity, after a delay.
     *
     * @param entity        The entity the action operates on.
     * @param action        The action to run.
     * @param delayTicks    Ticks to wait before running, minimum one tick.
     * @return A handle that can cancel the pending action, or {@link MVTask#NOOP} if the entity
     *         was already removed.
     */
    public @NotNull MVTask runAtEntityLater(@NotNull Entity entity, @NotNull Runnable action, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (!FOLIA) {
            return MVTask.of(Bukkit.getScheduler().runTaskLater(plugin, action, delay));
        }
        @Nullable var task = entity.getScheduler().runDelayed(plugin, ignored -> action.run(), null, delay);
        return task == null ? MVTask.NOOP : MVTask.of(task);
    }

    /**
     * Runs an action on the thread that owns the region containing the given location.
     *
     * @param location  The location the action operates on.
     * @param action    The action to run.
     */
    public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
        if (FOLIA) {
            Bukkit.getRegionScheduler().execute(plugin, location, action);
        } else if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    // ---------------------------------------------------------------------
    // Async
    // ---------------------------------------------------------------------

    /**
     * Runs an action off the server threads entirely.
     *
     * @param action    The action to run.
     * @return A handle that can cancel the action if it has not started.
     */
    public @NotNull MVTask runAsync(@NotNull Runnable action) {
        return FOLIA
                ? MVTask.of(Bukkit.getAsyncScheduler().runNow(plugin, task -> action.run()))
                : MVTask.of(Bukkit.getScheduler().runTaskAsynchronously(plugin, action));
    }

    /**
     * Runs an action off the server threads entirely, after a delay.
     *
     * @param action        The action to run.
     * @param delayTicks    Ticks to wait before running, minimum one tick.
     * @return A handle that can cancel the pending action.
     */
    public @NotNull MVTask runAsyncLater(@NotNull Runnable action, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        return FOLIA
                ? MVTask.of(Bukkit.getAsyncScheduler()
                        .runDelayed(plugin, task -> action.run(), delay * MILLIS_PER_TICK, TimeUnit.MILLISECONDS))
                : MVTask.of(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, action, delay));
    }
}
