/******************************************************************************
 * Multiverse 2 Copyright (c) the Multiverse Team 2011.                       *
 * Multiverse 2 is licensed under the BSD License.                            *
 * For more information please check the README.md file included              *
 * with this project.                                                         *
 ******************************************************************************/

package org.mvplugins.multiverse.core.utils.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * A server-implementation agnostic handle to a scheduled task.
 *
 * <p>Multiverse targets both regionised servers (Folia and its forks, such as Canvas) and
 * traditional single-main-thread servers. Those two families expose completely different task
 * handles - {@link ScheduledTask} and {@link BukkitTask} respectively - so this interface wraps
 * whichever one is in play and exposes only what Multiverse actually needs.</p>
 *
 * @since 5.3
 */
@FunctionalInterface
public interface MVTask {

    /**
     * A no-op task handle, used when a task completed inline and there is nothing to cancel.
     */
    MVTask NOOP = () -> { };

    /**
     * Cancels this task if it has not already run or been cancelled.
     */
    void cancel();

    /**
     * Wraps a Folia {@link ScheduledTask} into an {@link MVTask}.
     *
     * @param task  The Folia scheduled task to wrap.
     * @return The wrapped task handle.
     */
    static @NotNull MVTask of(@NotNull ScheduledTask task) {
        return task::cancel;
    }

    /**
     * Wraps a Bukkit {@link BukkitTask} into an {@link MVTask}.
     *
     * @param task  The Bukkit task to wrap.
     * @return The wrapped task handle.
     */
    static @NotNull MVTask of(@NotNull BukkitTask task) {
        return task::cancel;
    }
}
