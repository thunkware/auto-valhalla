package io.github.thunkware.auto.valhalla.maven;

import java.util.List;

/**
 * Nested compiler configuration options for the JDK 28 compiler.
 * Allows nesting under {@code <maven-compiler>} or {@code <compiler>}.
 */
public class CompilerConfiguration {

    private String encoding;
    private Boolean parameters;
    private Boolean debug;
    private String debuglevel;
    private Boolean showWarnings;
    private Boolean showDeprecation;
    private List<String> compilerArgs;
    private String compilerArgument;

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public Boolean getParameters() {
        return parameters;
    }

    public void setParameters(Boolean parameters) {
        this.parameters = parameters;
    }

    public Boolean getDebug() {
        return debug;
    }

    public void setDebug(Boolean debug) {
        this.debug = debug;
    }

    public String getDebuglevel() {
        return debuglevel;
    }

    public void setDebuglevel(String debuglevel) {
        this.debuglevel = debuglevel;
    }

    public Boolean getShowWarnings() {
        return showWarnings;
    }

    public void setShowWarnings(Boolean showWarnings) {
        this.showWarnings = showWarnings;
    }

    public Boolean getShowDeprecation() {
        return showDeprecation;
    }

    public void setShowDeprecation(Boolean showDeprecation) {
        this.showDeprecation = showDeprecation;
    }

    public List<String> getCompilerArgs() {
        return compilerArgs;
    }

    public void setCompilerArgs(List<String> compilerArgs) {
        this.compilerArgs = compilerArgs;
    }

    public String getCompilerArgument() {
        return compilerArgument;
    }

    public void setCompilerArgument(String compilerArgument) {
        this.compilerArgument = compilerArgument;
    }
}
