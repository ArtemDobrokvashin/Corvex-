package ru.corvex.mailpop3.server;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoopMetricFactoryTest {

    @Test
    void countersTrackLocalOperationsButFactoriesCreateIndependentMetrics() {
        var first = NoopMetricFactory.INSTANCE.generate("pop3.commands");
        var second = NoopMetricFactory.INSTANCE.generate("pop3.commands");

        assertNotSame(first, second);
        first.increment();
        first.add(5);
        first.decrement();
        first.remove(2);

        assertEquals(3, first.getCount());
        assertEquals(0, second.getCount());
    }

    @Test
    void timerReturnsNamedExecutionResultWithNonNegativeElapsedTime() {
        var timer = NoopMetricFactory.INSTANCE.timer("pop3.retr");

        var result = timer.stopAndPublish();

        assertEquals("pop3.retr", timer.name());
        assertFalse(result.elasped().isNegative());
        assertSame(result, result.logWhenExceedP99(Duration.ofSeconds(1)));
    }

    @Test
    void publisherDecoratorsReturnOriginalPublisher() {
        Publisher<String> publisher = subscriber -> {
        };

        assertSame(publisher,
                NoopMetricFactory.INSTANCE.decoratePublisherWithTimerMetric("timer", publisher));
        assertSame(publisher,
                NoopMetricFactory.INSTANCE.decoratePublisherWithTimerMetricLogP99("timer", publisher));
        assertTrue(NoopMetricFactory.INSTANCE == NoopMetricFactory.INSTANCE);
    }
}
