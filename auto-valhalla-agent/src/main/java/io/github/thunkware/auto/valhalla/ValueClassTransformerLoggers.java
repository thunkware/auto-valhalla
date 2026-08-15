package io.github.thunkware.auto.valhalla;

import io.github.thunkware.auto.valhalla.logger.InternalLogger;
import io.github.thunkware.auto.valhalla.logger.Level;

final class ValueClassTransformerLoggers {

    private final InternalLogger log = InternalLogger.getLogger(ValueClassTransformer.class);

    private final InternalLogger annotationSuccess =
            InternalLogger.getLogger("auto-valhalla.annotation.success");
    private final InternalLogger includesSuccess =
            InternalLogger.getLogger("auto-valhalla.includes.success");

    private final InternalLogger annotationRejected =
            InternalLogger.getLogger("auto-valhalla.annotation.rejected");
    private final InternalLogger includesRejected =
            InternalLogger.getLogger("auto-valhalla.includes.rejected");

    private final InternalLogger annotationFail =
            InternalLogger.getLogger("auto-valhalla.annotation.fail");
    private final InternalLogger includesFail =
            InternalLogger.getLogger("auto-valhalla.includes.fail");

    ValueClassTransformerLoggers() {
        InternalLogger.setLevelIfAbsent(annotationRejected.getName(), Level.FATAL);
        InternalLogger.setLevelIfAbsent(annotationFail.getName(), Level.FATAL);
        InternalLogger.setLevelIfAbsent(includesRejected.getName(), Level.DEBUG);
        InternalLogger.setLevelIfAbsent(includesFail.getName(), Level.DEBUG);
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
