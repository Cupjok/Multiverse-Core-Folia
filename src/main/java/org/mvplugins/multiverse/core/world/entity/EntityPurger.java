package org.mvplugins.multiverse.core.world.entity;

import jakarta.inject.Inject;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpawnCategory;
import org.jvnet.hk2.annotations.Service;
import org.jetbrains.annotations.NotNull;
import org.mvplugins.multiverse.core.utils.scheduler.MVScheduler;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;

import java.util.Set;
import java.util.function.Predicate;

@Service
public final class EntityPurger {

    private final MVScheduler scheduler;

    @Inject
    EntityPurger(@NotNull MVScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public int purgeEntities(LoadedMultiverseWorld world) {
        return purgeEntitiesWithCondition(world, entity -> !world.getEntitySpawnConfig().shouldAllowSpawn(entity));
    }

    public int purgeEntities(LoadedMultiverseWorld world, SpawnCategory spawnCategory) {
        return purgeEntitiesWithCondition(world, entity -> entity.getSpawnCategory().equals(spawnCategory));
    }

    public int purgeEntities(LoadedMultiverseWorld world, SpawnCategory... spawnCategories) {
        Set<SpawnCategory> spawnCategoriesSet = Set.of(spawnCategories);
        return purgeEntitiesWithCondition(world, entity -> spawnCategoriesSet.contains(entity.getSpawnCategory()));
    }

    public int purgeAllEntities(LoadedMultiverseWorld world) {
        return purgeEntitiesWithCondition(world, entity -> true);
    }

    private int purgeEntitiesWithCondition(LoadedMultiverseWorld world, Predicate<Entity> condition) {
        return Math.toIntExact(world.getBukkitWorld()
                .map(bukkitWorld -> bukkitWorld.getEntities().stream()
                        .filter(entity -> !(entity instanceof Player))
                        .filter(condition)
                        .peek(this::removeEntity)
                        .count())
                .getOrElse(0L));
    }

    /**
     * Removes an entity from the thread that owns it.
     *
     * <p>On a regionised server an entity may only be touched by the region currently ticking it,
     * which is not necessarily the thread that asked for the purge.</p>
     */
    private void removeEntity(@NotNull Entity entity) {
        scheduler.runAtEntity(entity, entity::remove);
    }
}
