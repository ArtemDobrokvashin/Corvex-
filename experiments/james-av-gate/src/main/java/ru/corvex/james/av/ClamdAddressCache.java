package ru.corvex.james.av;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Experimental non-blocking address snapshots. DNS and the JVM DNS cache remain external dependencies. */
final class ClamdAddressCache implements AutoCloseable {
    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }
    private record Snapshot(InetAddress[] addresses, long resolvedAt) { }
    private static final int MAX_ADDRESSES = 4;
    static final long MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final ScheduledThreadPoolExecutor REFRESHER = createExecutor();
    private final String host;
    private final Resolver resolver;
    private final LongSupplier clock;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ScheduledFuture<?> refreshTask;

    private static ScheduledThreadPoolExecutor createExecutor() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "corvex-clamd-dns");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    ClamdAddressCache(String host, InetAddress[] initial, Resolver resolver, LongSupplier clock) {
        this.host = Objects.requireNonNull(host);
        this.resolver = Objects.requireNonNull(resolver);
        this.clock = Objects.requireNonNull(clock);
        InetAddress[] validated = copyValid(initial);
        if (validated.length == 0) throw new IllegalArgumentException("Missing scanner addresses");
        current.set(new Snapshot(validated, clock.getAsLong()));
    }

    synchronized void start() {
        if (closed.get() || refreshTask != null) throw new IllegalStateException("Address cache already started or closed");
        refreshTask = REFRESHER.scheduleWithFixedDelay(this::refresh, 30, 30, TimeUnit.SECONDS);
    }

    // Called only on the single refresh worker; never from message-processing threads.
    void refresh() {
        if (closed.get()) return;
        try {
            InetAddress[] addresses = copyValid(resolver.resolve(host));
            if (addresses.length > 0 && !closed.get()) current.set(new Snapshot(addresses, clock.getAsLong()));
        } catch (UnknownHostException | RuntimeException ignored) {
            // Last snapshot expires; failed DNS never creates a clean verdict.
        }
    }

    InetAddress[] snapshot() {
        if (closed.get()) return new InetAddress[0];
        Snapshot snapshot = current.get();
        long age = clock.getAsLong() - snapshot.resolvedAt();
        if (age < 0 || age >= MAX_AGE_NANOS) return new InetAddress[0];
        return snapshot.addresses().clone();
    }

    private static InetAddress[] copyValid(InetAddress[] values) {
        if (values == null) return new InetAddress[0];
        return Arrays.stream(values).filter(Objects::nonNull).limit(MAX_ADDRESSES).toArray(InetAddress[]::new);
    }

    @Override public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            ScheduledFuture<?> task = refreshTask;
            if (task != null) task.cancel(false);
        }
    }
}
