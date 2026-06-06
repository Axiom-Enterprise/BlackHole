package org.bxteam.divinemc.async;

import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AsyncChunkSend {
    public static final ExecutorService POOL = DivineConfig.AsyncCategory.asyncChunkSendingEnabled ? createPool() : null;

    private static ThreadPoolExecutor createPool() {
        // JDK gotcha: a ThreadPoolExecutor with an UNBOUNDED queue never grows past its core size, so the
        // old (core=1, max=N, LinkedBlockingQueue) config always ran on a SINGLE thread regardless of
        // max-threads. That is fine for plain chunk serialization but bottlenecks when anti-xray
        // obfuscation is built here too (endless "loading terrain" on join). Use core=max so max-threads
        // actually parallelizes, with idle threads timing out so we do not keep them around when idle.
        final int threads = Math.max(1, DivineConfig.AsyncCategory.asyncChunkSendingMaxThreads);
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            threads, threads, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedAgnosticThreadFactory<>("Async Chunk Sending", AsyncChunkSendThread::new, Thread.NORM_PRIORITY),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public static class AsyncChunkSendThread extends Thread {
        protected AsyncChunkSendThread(ThreadGroup group, Runnable task, String name) {
            super(group, task, name);
        }
    }
}

