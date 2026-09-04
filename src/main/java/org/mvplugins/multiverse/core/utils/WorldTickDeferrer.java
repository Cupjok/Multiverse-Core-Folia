package org.mvplugins.multiverse.core.utils;

import com.dumptruckman.minecraft.util.Logging;
import io.vavr.control.Option;
import jakarta.inject.Inject;
import org.bukkit.Server;
import org.jetbrains.annotations.NotNull;
import org.jvnet.hk2.annotations.Service;

import org.mvplugins.multiverse.core.utils.scheduler.MVScheduler;

import java.lang.reflect.Field;

/**
 * Defers action that cannot be done during world tick.
 */
@Service
public final class WorldTickDeferrer {

    private final MVScheduler scheduler;

    private final Option<Object> console;
    private final Option<Field> isIteratingOverLevelsMethod;

    @Inject
    WorldTickDeferrer(@NotNull MVScheduler scheduler, @NotNull Server server) {
        this.scheduler = scheduler;
        this.console = ReflectHelper.tryGetMethod(server.getClass(), "getServer")
                .onFailure(throwable -> Logging.fine("Unable to find getServer method."))
                .flatMap(getServerMethod -> ReflectHelper.tryInvokeMethod(server, getServerMethod))
                .onFailure(throwable -> Logging.fine("Unable to find console."))
                .toOption();
        this.isIteratingOverLevelsMethod = console.toTry()
                .map(Object::getClass)
                .flatMap(consoleClazz -> ReflectHelper.tryGetField(consoleClazz, "isIteratingOverLevels"))
                .onFailure(throwable -> Logging.fine("Unable to find isIteratingOverLevels field."))
                .toOption();
    }

    /**
     * Defer action that cannot be done during world tick if needed.
     *
     * <p>On a regionised server the action is always moved off the server's tick threads. World
     * creation and unloading are only legal on the global region, and unloading additionally
     * completes on a server-owned teardown thread that the caller has to wait for - waiting on
     * the global region itself would deadlock. Running the whole operation off-tick lets it hop
     * onto the global region for the individual steps that need it, and block safely in between.</p>
     *
     * @param action The action to defer
     */
    public void deferWorldTick(Runnable action) {
        if (MVScheduler.isFolia()) {
            Logging.fine("Deferring world operation off the server tick threads...");
            scheduler.runAsync(action);
            return;
        }
        if (!isIteratingOverLevels()) {
            action.run();
            return;
        }
        Logging.fine("Deferring world tick...");
        scheduler.runGlobalLater(action, 1L);
    }

    /**
     * Check if the server is currently doing a world tick.
     *
     * @return True if the server is currently doing a world tick
     */
    private boolean isIteratingOverLevels() {
        return isIteratingOverLevelsMethod
                .flatMap(field -> console
                        .flatMap(c -> ReflectHelper.tryGetFieldValue(c, field, Boolean.class).toOption()))
                .getOrElse(false);
    }
}
