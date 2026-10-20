package ru.corvex.mailpop3.server;

import org.apache.james.metrics.api.Metric;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.metrics.api.TimeMetric;
import org.reactivestreams.Publisher;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * James-хендлеры требуют MetricFactory. Свои метрики Corvex отдаёт через Micrometer/Actuator,
 * поэтому для протокольного стека достаточно честного no-op без Dropwizard-зависимостей:
 * таймеры/счётчики ничего не публикуют, decorate* просто выполняет операцию.
 */
public class NoopMetricFactory implements MetricFactory {

    public static final NoopMetricFactory INSTANCE = new NoopMetricFactory();

    @Override
    public Metric generate(String name) {
        return new NoopMetric();
    }

    @Override
    public TimeMetric timer(String name) {
        return new NoopTimeMetric(name);
    }

    @Override
    public <T> Publisher<T> decoratePublisherWithTimerMetric(String name, Publisher<T> publisher) {
        return publisher;
    }

    @Override
    public <T> Publisher<T> decoratePublisherWithTimerMetricLogP99(String name, Publisher<T> publisher) {
        return publisher;
    }

    static final class NoopMetric implements Metric {
        private final AtomicLong count = new AtomicLong();

        @Override
        public void increment() {
            count.incrementAndGet();
        }

        @Override
        public void decrement() {
            count.decrementAndGet();
        }

        @Override
        public void add(int value) {
            count.addAndGet(value);
        }

        @Override
        public void remove(int value) {
            count.addAndGet(-value);
        }

        @Override
        public long getCount() {
            return count.get();
        }
    }

    static final class NoopTimeMetric implements TimeMetric {
        private final String name;
        private final long startNanos = System.nanoTime();

        NoopTimeMetric(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ExecutionResult stopAndPublish() {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            return new ExecutionResult() {
                @Override
                public Duration elasped() {
                    return elapsed;
                }

                @Override
                @SuppressWarnings("deprecation")
                public ExecutionResult logWhenExceedP99(Duration thresholdInNanoSeconds) {
                    return this;
                }
            };
        }
    }
}
