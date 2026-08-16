package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import io.github.thunkware.auto.valhalla.logger.InternalLoggerFactory;
import io.github.thunkware.auto.valhalla.util.Failable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAccumulator;

final class Stats {

    private static final InternalLogger log = InternalLoggerFactory.getLogger(Stats.class);

    private static final LongAccumulator transformTotalDuration = new LongAccumulator(Long::sum, 0L);
    private static final LongAccumulator transformTotalCount = new LongAccumulator(Long::sum, 0L);

    private static final LongAccumulator synchronizedOverheadDuration = new LongAccumulator(Long::sum, 0L);
    private static final LongAccumulator synchronizedCount = new LongAccumulator(Long::sum, 0L);

    static {
        Thread.ofVirtual()
              .name("auto-valhalla-Stats")
              .start(Stats::logStats);
    }

    public static void onValueClassTransform(final long durationNano) {
        transformTotalDuration.accumulate(durationNano);
        transformTotalCount.accumulate(1);
    }

    public static void onSynchronized(final long durationNano) {
        synchronizedOverheadDuration.accumulate(durationNano);
        synchronizedCount.accumulate(1);
    }

    public static Long transformTotalDurationMs() {
        return TimeUnit.NANOSECONDS.toMillis(transformTotalDuration.get());
    }

    public static Long transformTotalCount() {
        return transformTotalCount.get();
    }

    public static Long synchronizedOverheadDurationMs() {
        return TimeUnit.NANOSECONDS.toMillis(synchronizedOverheadDuration.get());
    }

    public static Long synchronizedCount() {
        return synchronizedCount.get();
    }

    private static void logStats() {
        while (!Thread.currentThread().isInterrupted()) {
            Failable.runQuietly(() -> TimeUnit.MINUTES.sleep(1));
            log.debug(String.format("Stats: transformTotalDuration=%s transformTotalCount=%s " +
                                            "synchronizedOverheadDuration=%s synchronizedCount=%s",
                                    transformTotalDurationMs(), transformTotalCount(),
                                    synchronizedOverheadDurationMs(), synchronizedCount.get()));
        }
    }
}
