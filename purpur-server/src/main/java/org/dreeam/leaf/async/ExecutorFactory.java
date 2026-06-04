package org.dreeam.leaf.async;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.DefaultUncaughtExceptionHandlerWithName;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Purpur - async playerdata save (Leaf)
// Adapted from Leaf's org.dreeam.leaf.async.ExecutorFactory. This helper is part of Leaf's broader
// async toolkit and is NOT used by the async player-data saving feature itself (AsyncPlayerDataSaving
// builds its own ThreadPoolExecutor). It is vendored here for completeness alongside the other async
// helpers, but adapted to compile against the 26.1.2 (DivineMC) base, which differs from Leaf 1.21.11:
//   - The org.dreeam.leaf.config VirtualThreadSupport gate and every Thread.ofVirtual() branch are
//     removed: that config class is intentionally not vendored, and the virtual-thread branches also
//     referenced io.papermc.paper.threadedregions.scheduler.FoliaAsyncScheduler, which does not exist
//     on this base. The non-virtual-thread (Paper-default) bodies are retained.
//   - buildPaperConfigurationPool(...) is dropped because it referenced
//     io.papermc.paper.connection.PaperConfigurationTask, which does not exist on this base.
//   - net.minecraft.util.Util.LOGGER and ServerLoginPacketListenerImpl.LOGGER are package-private on
//     this base (public/accessible on Leaf via unrelated patches), so the auth/download pools log
//     through the public MinecraftServer.LOGGER instead.
public class ExecutorFactory {

    public static ExecutorService buildChatExecutor() {
        return Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Async Chat Thread - #%d")
                .setThreadFactory(Executors.defaultThreadFactory())
                .setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandlerWithName(MinecraftServer.LOGGER))
                .build()
        ); // Paper
    }

    public static ExecutorService buildAuthPoolExecutor() {
        return Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
                .setNameFormat("User Authenticator #%d")
                .setThreadFactory(Executors.defaultThreadFactory())
                .setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(MinecraftServer.LOGGER))
                .build()
        ); // Paper - Cache authenticator threads
    }

    public static ExecutorService buildDownloadPoolExecutor() {
        return Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger();

            @Override
            public Thread newThread(@NonNull Runnable run) {
                Thread ret = new Thread(run);
                ret.setDaemon(true);
                ret.setName("Download-" + this.count.getAndIncrement());
                ret.setUncaughtExceptionHandler((Thread thread, Throwable throwable) -> MinecraftServer.LOGGER.error("Uncaught exception in thread {}", thread.getName(), throwable));
                return ret;
            }
        }); // Paper - Limit download pool size
    }

    public static ExecutorService buildBukkitAsyncSchedulerExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            4, Integer.MAX_VALUE, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            new ThreadFactoryBuilder().setNameFormat("Craft Scheduler Thread - %1$d").build()
        );

        executor.allowCoreThreadTimeOut(true);
        executor.prestartAllCoreThreads();
        return executor;
    }

    public static ExecutorService buildFoliaAsyncSchedulerExecutor() {
        return new ThreadPoolExecutor(Math.max(4, Runtime.getRuntime().availableProcessors() / 2), Integer.MAX_VALUE,
            30L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            new ThreadFactory() {
                private final AtomicInteger idGenerator = new AtomicInteger();

                @Override
                public Thread newThread(final @NonNull Runnable run) {
                    final Thread ret = new Thread(run);

                    ret.setName("Folia Async Scheduler Thread #" + this.idGenerator.getAndIncrement());
                    ret.setPriority(Thread.NORM_PRIORITY - 1);
                    ret.setUncaughtExceptionHandler((final Thread thread, final Throwable thr) -> LoggerFactory.getLogger("FoliaAsyncScheduler").error("Uncaught exception in thread: {}", thread.getName(), thr));

                    return ret;
                }
            }
        );
    }
}
