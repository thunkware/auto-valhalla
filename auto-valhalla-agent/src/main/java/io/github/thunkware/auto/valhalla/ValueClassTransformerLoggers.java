package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import io.github.thunkware.auto.valhalla.logger.InternalLoggerFactory;
import io.github.thunkware.auto.valhalla.logger.Level;

final class ValueClassTransformerLoggers {

    private final InternalLogger log = InternalLoggerFactory.getLogger(ValueClassTransformer.class);

    private final InternalLogger annotationSuccess =
            InternalLoggerFactory.getLogger("auto-valhalla.annotation.success");
    private final InternalLogger includesSuccess =
            InternalLoggerFactory.getLogger("auto-valhalla.includes.success");

    private final InternalLogger annotationRejected =
            InternalLoggerFactory.getLogger("auto-valhalla.annotation.rejected");
    private final InternalLogger includesRejected =
            InternalLoggerFactory.getLogger("auto-valhalla.includes.rejected");

    private final InternalLogger annotationFail =
            InternalLoggerFactory.getLogger("auto-valhalla.annotation.fail");
    private final InternalLogger includesFail =
            InternalLoggerFactory.getLogger("auto-valhalla.includes.fail");

    ValueClassTransformerLoggers() {
        InternalLoggerFactory.setLevelIfAbsent(annotationRejected.getName(), Level.FATAL);
        InternalLoggerFactory.setLevelIfAbsent(annotationFail.getName(), Level.FATAL);
        InternalLoggerFactory.setLevelIfAbsent(includesRejected.getName(), Level.DEBUG);
        InternalLoggerFactory.setLevelIfAbsent(includesFail.getName(), Level.DEBUG);
    }

    InternalLogger log() {
        return log;
    }

    InternalLogger rejected(ValueClassTransformer.Selection selection) {
        return selection.annotated() ? annotationRejected : includesRejected;
    }

    InternalLogger success(ValueClassTransformer.Selection selection) {
        return selection.annotated() ? annotationSuccess : includesSuccess;
    }

    InternalLogger fail(ValueClassTransformer.Selection selection) {
        return selection.annotated() ? annotationFail : includesFail;
    }
}
