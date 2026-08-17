package io.github.thunkware.auto.valhalla;

import static io.github.thunkware.auto.valhalla.Mode.SYNCHRONIZATION_MONITOR;

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

    public static void accept(final Config cfg) {
        boolean inSynchronizedMonitorMode = cfg.annotationMode.contains(SYNCHRONIZATION_MONITOR)
                || cfg.includesMode.contains(SYNCHRONIZATION_MONITOR);
        Thread.ofVirtual()
              .name("auto-valhalla-Stats")
              .start(() -> logStats(inSynchronizedMonitorMode));
    }

    public static void onValueClassTransform(final long durationNano) {
        transformTotalDuration.accumulate(durationNano);
        transformTotalCount.accumulate(1);
    }

    public static void onSynchronized(final long durationNano) {
        synchronizedOverheadDuration.accumulate(durationNano);
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

    private static void logStats(boolean inSynchronizedMonitorMode) {
        String template = "Stats: transformTotalDuration=%sms transformTotalCount=%s";
        if (inSynchronizedMonitorMode) {
            template = template + " synchronizedOverheadDuration=%sms";
        }

        // undocumented property
        long ms = Long.getLong(Stats.class.getName() + ".sleep", TimeUnit.MINUTES.toMillis(1));
        while (!Thread.currentThread().isInterrupted()) {
            Failable.runQuietly(() -> TimeUnit.MILLISECONDS.sleep(ms));
            if (log.isDebugEnabled()) {
                log.debug(String.format(template,
                                        transformTotalDurationMs(), transformTotalCount(),
                                        synchronizedOverheadDurationMs()));
            }
        }
    }

}
