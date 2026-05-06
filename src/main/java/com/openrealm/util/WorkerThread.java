package com.openrealm.util;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WorkerThread {
    // Three-pool strategy so a burst of synchronous HTTP saves (mass logout
    // / realm shutdown / vault-exit storm) can't park every worker and
    // starve the 64Hz tick fan-out at the same time.
    //
    // MAIN: general-purpose, sized for IO-bound workloads. Most submissions
    //       (packet handlers, ad-hoc background work) land here. Large cap
    //       because each thread mostly sits in HttpClient.send() waiting
    //       on the data service.
    //
    // IO_BLOCKING: dedicated pool for blocking HTTP calls into
    //       openrealm-data (chest saves, character persistence, fame
    //       updates, account fetches). Sized to handle a burst of every
    //       active player simultaneously hitting the data service. Keeping
    //       this separate from MAIN means a chest-save storm can't drain
    //       the threads tick fan-out is using.
    //
    // CPU_TICK: sized to physical cores so realm-tick parallel sections
    //       (per-realm enemy AI, projectile sims) don't oversubscribe
    //       and start trampling each other's CPU caches.
    private static final int MAIN_POOL_COUNT = Math.min(Runtime.getRuntime().availableProcessors() * 16, 200);
    private static final int IO_POOL_COUNT   = 128;
    private static final int CPU_POOL_COUNT  = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);

    private static final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors
            .newFixedThreadPool(MAIN_POOL_COUNT, Executors.privilegedThreadFactory());
    private static final ThreadPoolExecutor ioExecutor = (ThreadPoolExecutor) Executors
            .newFixedThreadPool(IO_POOL_COUNT, namedFactory("openrealm-io"));
    private static final ThreadPoolExecutor cpuExecutor = (ThreadPoolExecutor) Executors
            .newFixedThreadPool(CPU_POOL_COUNT, namedFactory("openrealm-tick"));

    static {
        log.info("[WorkerThread] pools: main={} io={} cpu={} (cores={})",
                MAIN_POOL_COUNT, IO_POOL_COUNT, CPU_POOL_COUNT,
                Runtime.getRuntime().availableProcessors());
    }

    private static ThreadFactory namedFactory(String prefix) {
        final AtomicInteger n = new AtomicInteger(0);
        final ThreadFactory base = Executors.privilegedThreadFactory();
        return r -> {
            Thread t = base.newThread(r);
            t.setName(prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Synchronous-but-IO-heavy work — the data-service REST calls. Use this
     *  instead of {@link #submit(Runnable)} for anything that calls
     *  HttpClient.send() inside, so a burst of those can't drain the main
     *  pool while the tick is trying to fan out. */
    public static CompletableFuture<Void> submitIo(Runnable r) {
        if (r == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(r, ioExecutor);
    }

    /** CPU-bound parallel section (e.g. realm-tick fan-out). */
    public static CompletableFuture<Void> submitCpu(Runnable r) {
        if (r == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(r, cpuExecutor);
    }

    /** Returns the IO-pool executor for use with HttpClient.sendAsync(...).
     *  Calling sendAsync(handler, IO_EXECUTOR) keeps response-decode work
     *  off the JDK's shared HttpClient default pool. */
    public static Executor ioExecutor() {
        return ioExecutor;
    }

    public static CompletableFuture<?> submit(Runnable runnable) {
        if (runnable == null)
            return null;
        return CompletableFuture.runAsync(runnable, WorkerThread.executor);
    }
    
    public static void runLater(Runnable runnable, long ms) {
    	final Runnable wrappedTask = () ->{
    		try{
    			Thread.sleep(ms);
    			runnable.run();
    		}catch(Exception e) {
    			log.error("Failed to execute runnable in the future. Reason: {}", e.getMessage());
    		}
    	};
    	submitAndForkRun(wrappedTask);		
    }

    public static CompletableFuture<Void> doAsync(Runnable task) {
        final CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor);
        return cf;
    }

    public static void submit(Thread runnable) {
        if (runnable == null)
            return;

        WorkerThread.executor.execute(runnable);
    }

    public static void submitRunnable(Runnable runnable) {
        if (runnable == null)
            return;

        WorkerThread.executor.execute(runnable);
    }

    public static void allOf(CompletableFuture<?>... futures) {
        if (futures == null)
            return;

        CompletableFuture<Void> cf = CompletableFuture.allOf(futures);
        try {
            // WorkerThread.log.info("Completing {} asynchronous tasks",
            // futures.length);
            cf.join();
        } catch (Exception e) {
            WorkerThread.log.error("Failed to complete async tasks {}", e);
        }
    }

    public static void submitAndRun(Runnable... runnables) {
        if (runnables == null)
            return;
        // Single task: run inline, no pool overhead
        if (runnables.length == 1) {
            runnables[0].run();
            return;
        }
        // Multiple tasks: fan out N-1 to pool, run last one on current thread
        CompletableFuture<?>[] futures = new CompletableFuture[runnables.length - 1];
        for (int i = 0; i < runnables.length - 1; i++) {
            futures[i] = WorkerThread.submit(runnables[i]);
        }
        // Run the last task on the calling thread while others execute in parallel
        runnables[runnables.length - 1].run();
        WorkerThread.allOf(futures);
    }

    public static void submitAndRun(List<Runnable> runnables) {
        if (runnables == null)
            return;
        if (runnables.size() == 1) {
            runnables.get(0).run();
            return;
        }
        CompletableFuture<?>[] futures = new CompletableFuture[runnables.size() - 1];
        for (int i = 0; i < runnables.size() - 1; i++) {
            futures[i] = WorkerThread.submit(runnables.get(i));
        }
        runnables.get(runnables.size() - 1).run();
        WorkerThread.allOf(futures);
    }

    /*
     * Submits runnables that execute in an newly forked thread (good for long
     * running tasks)
     */
    public static CompletableFuture<?>[] submitAndForkRun(Runnable... runnables) {
        if (runnables == null)
            return null;
        
        final CompletableFuture<?>[] futures = new CompletableFuture[runnables.length];

        for (int i = 0; i < runnables.length; i++) {
           futures[i] = WorkerThread.submit(runnables[i]);
        }
        return futures;
    }
}
