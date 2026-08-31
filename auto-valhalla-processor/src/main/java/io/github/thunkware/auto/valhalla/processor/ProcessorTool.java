package io.github.thunkware.auto.valhalla.processor;

import java.net.URI;
import java.nio.file.Paths;
import java.security.ProtectionDomain;

public class ProcessorTool {

    /**
     * The {@code -A} option for output directory
     */
    public static final String OPT_OUTDIR = "outdir";

    /**
     * The {@code -A} option for removing {@code @AutoValhalla} annotation from generated source.
     */
    public static final String OPT_REMOVE_ANNOTATION = "removeAnnotation";

    public static final String PROCESSOR_NAME = ProcessorTool.class.getPackage().getName() + ".AutoValhallaProcessor";

    private ProcessorTool() {
        throw new AssertionError();
    }

    /**
     * The absolute path of the location this class was loaded from (the jar or
     * class directory to hand to {@code javac -processorpath}), or {@code null}
     * when it cannot be determined. The maven plugin passes this path through
     * to its {@code javac -proc:only} selection pass.
     */
    public static String processorPath() {
        ProtectionDomain protectionDomain = ProcessorTool.class.getProtectionDomain();
        if (protectionDomain == null || protectionDomain.getCodeSource() == null) {
            return null;
        }
        try {
            URI uri = protectionDomain.getCodeSource().getLocation().toURI();
            return Paths.get(uri).toFile().getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }
}
