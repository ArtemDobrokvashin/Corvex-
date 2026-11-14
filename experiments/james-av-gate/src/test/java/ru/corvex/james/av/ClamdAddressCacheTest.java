package ru.corvex.james.av;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Synthetic resolver and fake clock; not a Docker DNS or James runtime test. */
class ClamdAddressCacheTest {
    private static InetAddress ip(int last) throws Exception {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, (byte) last});
    }

    @Test void refreshReplacesAddressAndNeverExposesMutableArrays() throws Exception {
        AtomicLong time = new AtomicLong(1);
        InetAddress first = ip(1), second = ip(2);
        AtomicReference<InetAddress[]> answers = new AtomicReference<>(new InetAddress[]{second});
        var cache = new ClamdAddressCache("clamav", new InetAddress[]{first}, host -> answers.get(), time::get);
        InetAddress[] snapshot = cache.snapshot();
        snapshot[0] = second;
        assertEquals(first, cache.snapshot()[0]);
        cache.refresh();
        answers.get()[0] = first;
        assertEquals(second, cache.snapshot()[0]);
        cache.close();
        assertEquals(0, cache.snapshot().length);
    }

    @Test void staleSnapshotExpiresAndSuccessfulRefreshRecovers() throws Exception {
        AtomicLong time = new AtomicLong(1);
        AtomicReference<InetAddress[]> answers = new AtomicReference<>(new InetAddress[]{ip(2)});
        var cache = new ClamdAddressCache("clamav", new InetAddress[]{ip(1)}, host -> {
            if (answers.get() == null) throw new UnknownHostException("synthetic");
            return answers.get();
        }, time::get);
        answers.set(null);
        cache.refresh();
        time.addAndGet(ClamdAddressCache.MAX_AGE_NANOS);
        assertEquals(0, cache.snapshot().length);
        answers.set(new InetAddress[]{ip(2)});
        cache.refresh();
        assertArrayEquals(new InetAddress[]{ip(2)}, cache.snapshot());
        cache.close();
    }

    @Test void blockedResolverDoesNotBlockSnapshotOrPreventExpiry() throws Exception {
        AtomicLong time = new AtomicLong(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var cache = new ClamdAddressCache("clamav", new InetAddress[]{ip(1)}, host -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new UnknownHostException("timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new UnknownHostException("interrupted"); }
            try { return new InetAddress[]{ip(2)}; } catch (Exception e) { throw new UnknownHostException("bad address"); }
        }, time::get);
        Thread worker = new Thread(cache::refresh);
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(ip(1), cache.snapshot()[0]);
            time.addAndGet(ClamdAddressCache.MAX_AGE_NANOS);
            assertEquals(0, cache.snapshot().length);
            cache.close();
        } finally { release.countDown(); worker.join(3000); }
        assertFalse(worker.isAlive());
        assertEquals(0, cache.snapshot().length);
    }
}
