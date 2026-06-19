package com.infernalsuite.asp.level;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkLoadTask;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.GenericDataLoadTask;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.infernalsuite.asp.Converter;
import com.infernalsuite.asp.level.moonrise.ChunkDataLoadTask;
import com.infernalsuite.asp.level.moonrise.SlimeEntityDataLoader;
import com.infernalsuite.asp.level.moonrise.SlimePoiDataLoader;
import com.infernalsuite.asp.serialization.slime.SlimeSerializer;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.mojang.logging.LogUtils;
import io.papermc.paper.world.PaperWorldLoader;
import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.util.TriState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.validation.DirectoryValidator;
import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.event.world.WorldSaveEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spigotmc.AsyncCatcher;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class SlimeLevelInstance extends ServerLevel {

    public static LevelStorageSource CUSTOM_LEVEL_STORAGE;
    private static final Logger LOGGER = LogUtils.getClassLogger();

    static {
        try {
            Path path = Files.createTempDirectory("swm-" + UUID.randomUUID().toString().substring(0, 5)).toAbsolutePath();
            DirectoryValidator directoryvalidator = LevelStorageSource.parseValidator(path.resolve("allowed_symlinks.txt"));
            CUSTOM_LEVEL_STORAGE = new LevelStorageSource(path, path, directoryvalidator, DataFixers.getDataFixer());
            FileUtils.forceDeleteOnExit(path.toFile());
        } catch (IOException ex) {
            throw new IllegalStateException("Couldn't create dummy file directory.", ex);
        }
    }

    private static final ExecutorService WORLD_SAVER_SERVICE = Executors.newFixedThreadPool(4, new ThreadFactoryBuilder()
            .setNameFormat("SWM Pool Thread #%1$d").build());

    private final Object saveLock = new Object();
    private final LevelStorageSource.LevelStorageAccess levelStorageAccess;

    public SlimeLevelInstance(SlimeBootstrap slimeBootstrap, PrimaryLevelData primaryLevelData,
                              ResourceKey<net.minecraft.world.level.Level> worldKey,
                              ResourceKey<LevelStem> dimensionKey, LevelStem worldDimension,
                              org.bukkit.World.Environment environment) throws IOException {

        // Java 25 flexible constructor bodies (JEP 513): compute locals before super()
        final MinecraftServer server = MinecraftServer.getServer();
        final String worldName = slimeBootstrap.initial().getName();

        final LevelStorageSource.LevelStorageAccess storageAccess =
                CUSTOM_LEVEL_STORAGE.createAccess(worldName + UUID.randomUUID(), dimensionKey);

        // Build biome holder for the slime generator
        final String biomeStr = slimeBootstrap.initial().getPropertyMap().getValue(SlimeProperties.DEFAULT_BIOME);
        final ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, Identifier.parse(biomeStr));
        final Holder<Biome> defaultBiome = server.registryAccess()
                .lookupOrThrow(Registries.BIOME).get(biomeKey).orElseThrow();
        final SlimeLevelGenerator slimeGenerator = new SlimeLevelGenerator(defaultBiome);

        // Custom LevelStem uses our slime generator
        final LevelStem customStem = new LevelStem(worldDimension.type(), slimeGenerator);

        // WorldGenSettings - flat world options, seed 0
        final WorldGenSettings worldGenSettings = WorldGenSettings.of(
                new WorldOptions(0, false, false), server.registryAccess());

        // In-memory saved data storage (no disk I/O)
        final Path dataPath = storageAccess.getLevelPath(LevelResource.DATA);
        Files.createDirectories(dataPath);
        final ReadOnlyDimensionDataStorage savedDataStorage = new ReadOnlyDimensionDataStorage(
                dataPath, DataFixers.getDataFixer(), server.registryAccess());

        // LoadedWorldData wrapping PaperLevelOverrides
        final PaperLevelOverrides levelOverrides = PaperLevelOverrides
                .createFromLiveLevelData((PrimaryLevelData) server.getWorldData())
                .attach((PrimaryLevelData) server.getWorldData(), worldKey);
        final PaperWorldLoader.LoadedWorldData loadedWorldData =
                new PaperWorldLoader.LoadedWorldData(worldName, UUID.randomUUID(), null, levelOverrides);

        super(server, server.executor, storageAccess, worldGenSettings, worldKey, customStem,
                false, 0, Collections.emptyList(), true, dimensionKey, environment,
                null, null, savedDataStorage, loadedWorldData);

        this.levelStorageAccess = storageAccess;

        // Wire level reference on generator now that `this` is available
        slimeGenerator.setLevel(this);

        this.slimeInstance = new SlimeInMemoryWorld(slimeBootstrap, this);

        final SlimePropertyMap propertyMap = slimeBootstrap.initial().getPropertyMap();

        this.serverLevelData.setDifficulty(
                Difficulty.valueOf(propertyMap.getValue(SlimeProperties.DIFFICULTY).toUpperCase()));
        this.serverLevelData.setSpawn(
                new LevelData.RespawnData(
                        GlobalPos.of(
                                ResourceKey.create(Registries.DIMENSION, this.dimension().identifier()),
                                new BlockPos(
                                        propertyMap.getValue(SlimeProperties.SPAWN_X),
                                        propertyMap.getValue(SlimeProperties.SPAWN_Y),
                                        propertyMap.getValue(SlimeProperties.SPAWN_Z)
                                )
                        ),
                        Mth.wrapDegrees(propertyMap.getValue(SlimeProperties.SPAWN_YAW)),
                        Mth.wrapDegrees(0F)
                )
        );

        super.chunkSource.setSpawnSettings(
                propertyMap.getValue(SlimeProperties.ALLOW_MONSTERS),
                propertyMap.getValue(SlimeProperties.ALLOW_ANIMALS));

        // Read PDC from extra data
        java.util.concurrent.ConcurrentMap<String, BinaryTag> extraData = this.slimeInstance.getExtraData();
        if (extraData.containsKey("BukkitValues")) {
            getWorld().readBukkitValues(Converter.convertTag(extraData.get("BukkitValues")));
        }

        propertyMap.getOptionalValue(SlimeProperties.PVP)
                .ifPresent(val -> getGameRules().set(GameRules.PVP, val, this));

        // Override entity/poi data controllers with slime-specific implementations
        this.entityDataController = new SlimeEntityDataLoader(
                new ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.EntityDataController.EntityRegionFileStorage(
                        new RegionStorageInfo(storageAccess.getLevelId(), worldKey, "entities"),
                        storageAccess.getDimensionPath(worldKey).resolve("entities"),
                        server.forceSynchronousWrites()
                ),
                this.chunkTaskScheduler,
                this
        );
        this.poiDataController = new SlimePoiDataLoader(this, this.chunkTaskScheduler);
    }

    @Override
    public @NotNull net.minecraft.world.level.chunk.ChunkGenerator getGenerator(SlimeBootstrap slimeBootstrap) {
        return null; // generator already set via custom LevelStem in constructor
    }

    @Override
    public void save(@Nullable ProgressListener progressUpdate, boolean forceSave, boolean savingDisabled, boolean close) {
        if (!savingDisabled) save();
    }

    public void unload(@NotNull LevelChunk chunk, ChunkEntitySlices slices, PoiChunk poiChunk) {
        slimeInstance.unload(chunk, slices, poiChunk);
    }

    @Override
    public void saveIncrementally(boolean doFull) {
        if (doFull) {
            save();
        }
    }

    public Future<?> save() {
        AsyncCatcher.catchOp("SWM world save");
        try {
            if (!this.slimeInstance.isReadOnly() && this.slimeInstance.getLoader() != null) {
                Bukkit.getPluginManager().callEvent(new WorldSaveEvent(getWorld()));

                this.serverLevelData.setCustomBossEvents(
                        MinecraftServer.getServer().getCustomBossEvents()
                                .save(MinecraftServer.getServer().registryAccess()));

                if (MinecraftServer.getServer().isStopped()) {
                    saveInternal().get();
                } else {
                    return this.saveInternal();
                }
            }
        } catch (Throwable e) {
            LOGGER.error("There was a problem saving the SlimeLevelInstance {}", serverLevelData.getLevelName(), e);
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.completedFuture(null);
    }

    private Future<?> saveInternal() {
        synchronized (saveLock) {
            SlimeWorldInstance slimeWorld = this.slimeInstance;
            LOGGER.debug("Saving world {}...", this.slimeInstance.getName());
            long start = System.currentTimeMillis();

            SlimeWorld world = this.slimeInstance.getSerializableCopy();
            return WORLD_SAVER_SERVICE.submit(() -> {
                try {
                    byte[] serializedWorld = SlimeSerializer.serialize(world);
                    long saveStart = System.currentTimeMillis();
                    slimeWorld.getLoader().saveWorld(slimeWorld.getName(), serializedWorld);
                    LOGGER.debug("World {} serialized in {}ms and saved in {}ms.",
                            slimeWorld.getName(), saveStart - start, System.currentTimeMillis() - saveStart);
                } catch (Exception ex) {
                    LOGGER.error("There was an issue saving world {} asynchronously.", slimeWorld.getName(), ex);
                }
            });
        }
    }

    public SlimeWorldInstance getSlimeInstance() {
        return this.slimeInstance;
    }

    public ChunkDataLoadTask getLoadTask(ChunkLoadTask task, ChunkTaskScheduler scheduler, ServerLevel world,
                                         int chunkX, int chunkZ, Priority priority,
                                         Consumer<GenericDataLoadTask.TaskResult<ChunkAccess, Throwable>> onRun) {
        return new ChunkDataLoadTask(task, scheduler, world, chunkX, chunkZ, priority, onRun);
    }

    public void deleteTempFiles() {
        WORLD_SAVER_SERVICE.execute(() -> {
            Path path = this.levelStorageAccess.levelDirectory.path();
            try {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public @NotNull FileVisitResult visitFile(Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                        if (!file.equals(path)) {
                            Files.deleteIfExists(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public @NotNull FileVisitResult postVisitDirectory(Path dir,
                            @javax.annotation.Nullable IOException exception) throws IOException {
                        if (exception != null) throw exception;
                        if (dir.equals(levelStorageAccess.levelDirectory.path())) {
                            Files.deleteIfExists(path);
                        }
                        Files.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                LOGGER.warn("Unable to delete temp level directory", e);
            }
        });
    }
}
