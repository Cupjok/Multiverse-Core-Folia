package org.mvplugins.multiverse.core.utils.compatibility;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.dumptruckman.minecraft.util.Logging;
import io.vavr.control.Try;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import org.mvplugins.multiverse.core.utils.ReflectHelper;

/**
 * Compatibility class used to unload a Bukkit world across server implementations that disagree
 * about how - or whether - a world may be unloaded at runtime.
 *
 * <p>Regionised servers cannot unload a world synchronously, because every region currently
 * ticking that world has to be brought down first. Canvas therefore replaces the synchronous
 * {@link Bukkit#unloadWorld(World, boolean)} - which throws
 * {@link UnsupportedOperationException} - with an asynchronous
 * {@code unloadWorldAsync(World, boolean, Consumer)} that reports its outcome through a callback
 * once the world has actually been torn down.</p>
 *
 * <p>The Canvas entry point is resolved reflectively so that this fork keeps working on plain
 * Folia, Paper and Spigot, where the synchronous call is still the correct one.</p>
 *
 * @since 5.3
 */
@ApiStatus.AvailableSince("5.3")
public final class WorldUnloadCompatibility {

    private static final Try<Method> UNLOAD_WORLD_ASYNC_METHOD;

    static {
        UNLOAD_WORLD_ASYNC_METHOD = ReflectHelper.tryGetMethod(
                Bukkit.getServer().getClass(), "unloadWorldAsync", World.class, boolean.class, Consumer.class);
    }

    /**
     * Checks whether this server unloads worlds asynchronously through the Canvas API.
     *
     * @return True if the asynchronous unload entry point is available.
     *
     * @since 5.3
     */
    @ApiStatus.AvailableSince("5.3")
    public static boolean isAsyncUnloadSupported() {
        return UNLOAD_WORLD_ASYNC_METHOD.isSuccess();
    }

    /**
     * Unloads a Bukkit world, using the asynchronous Canvas entry point when it is available and
     * the synchronous Bukkit one otherwise.
     *
     * <p>On a regionised server this <b>must</b> be called from the global region thread, and the
     * returned future completes later on a server-owned teardown thread. Callers must therefore
     * never block the global region thread on the result - doing so deadlocks the failure path,
     * which needs the global region to make progress.</p>
     *
     * @param world The world to unload.
     * @param save  Whether to save the world before unloading it.
     * @return A future completed with the unload outcome.
     *
     * @since 5.3
     */
    @ApiStatus.AvailableSince("5.3")
    public static @NotNull CompletableFuture<Result> unloadWorld(@NotNull World world, boolean save) {
        return UNLOAD_WORLD_ASYNC_METHOD
                .map(method -> unloadAsync(method, world, save))
                .getOrElse(() -> CompletableFuture.completedFuture(unloadSync(world, save)));
    }

    private static @NotNull CompletableFuture<Result> unloadAsync(
            @NotNull Method method, @NotNull World world, boolean save) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        Consumer<Object> callback = result -> future.complete(Result.fromCanvasResult(result));
        return ReflectHelper.<Object, Void>tryInvokeMethod(Bukkit.getServer(), method, world, save, callback)
                .map(ignored -> future)
                .getOrElseGet(throwable -> {
                    Logging.severe("Failed to start async unload of world '%s': %s",
                            world.getName(), throwable.getMessage());
                    return CompletableFuture.completedFuture(
                            new Result(false, "async unload could not be started: " + throwable.getMessage()));
                });
    }

    private static @NotNull Result unloadSync(@NotNull World world, boolean save) {
        return Bukkit.unloadWorld(world, save)
                ? new Result(true, "SUCCESS")
                : new Result(false, "server refused to unload the world");
    }

    /**
     * The outcome of a world unload, normalised across server implementations.
     *
     * @param success   Whether the world was actually unloaded.
     * @param reason    A human readable description of the outcome, for logging and error messages.
     *
     * @since 5.3
     */
    @ApiStatus.AvailableSince("5.3")
    public record Result(boolean success, @NotNull String reason) {

        private static final String SUCCESS_NAME = "SUCCESS";

        private static @NotNull Result fromCanvasResult(Object canvasResult) {
            if (canvasResult == null) {
                return new Result(false, "server reported no unload result");
            }
            String name = canvasResult.toString();
            if (SUCCESS_NAME.equals(name)) {
                return new Result(true, name);
            }
            return new Result(false, describeFailure(name));
        }

        private static @NotNull String describeFailure(@NotNull String name) {
            return switch (name) {
                case "FAIL_PLAYERS_JOINING" -> "players are still joining the world";
                case "FAIL_PLAYERS_PRESENT" -> "players are still in the world";
                case "FAIL_ALREADY_UNLOADING" -> "the world is already being unloaded";
                case "FAIL_IS_OVERWORLD" -> "the primary overworld cannot be unloaded";
                case "FAIL_UNLOAD_EVENT" -> "another plugin cancelled the unload";
                case "FAIL_IS_SHUTDOWN" -> "the server is shutting down";
                default -> name.toLowerCase(Locale.ENGLISH).replace('_', ' ');
            };
        }
    }

    private WorldUnloadCompatibility() {
        throw new IllegalStateException("Utility class");
    }
}
