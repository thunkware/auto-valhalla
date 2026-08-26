package io.github.thunkware.auto.valhalla.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

/**
 * Detects the maven-jar-plugin version in use and warns when it predates
 * {@link #JAR_PLUGIN_MRJ_VERSION}, the first release that turns a jar
 * containing {@code META-INF/versions} into a proper multi-release jar on its
 * own. Runs at most once per Maven run (guarded by a marker in the session's
 * user properties), no matter how many modules or auto-valhalla goals fire.
 */
final class JarPluginChecker {

    /** First maven-jar-plugin release with automatic multi-release jar
     *  handling based on the META-INF/versions directory. */
    static final int[] JAR_PLUGIN_MRJ_VERSION = {3, 4, 0};

    private static final String CHECKED_KEY = "auto-valhalla.jarPluginVersionChecked";

    private JarPluginChecker() {
        throw new AssertionError();
    }

    /**
     * Looks the plugin up in the project's {@code <build><plugins>} first,
     * then in its {@code pluginManagement} (which also carries the default
     * version pinned by Maven's super POM when the project never declares the
     * jar plugin).
     */
    static void checkOnce(MavenSession session, MavenProject project, Log log) {
        if (session.getUserProperties().containsKey(CHECKED_KEY)) {
            return;
        }
        session.getUserProperties().setProperty(CHECKED_KEY, "true");
        String pluginKey = "org.apache.maven.plugins:maven-jar-plugin";
        Plugin jarPlugin = project.getPlugin(pluginKey);
        if (jarPlugin == null) {
            PluginManagement management = project.getPluginManagement();
            if (management != null) {
                jarPlugin = management.getPluginsAsMap().get(pluginKey);
            }
        }
        if (jarPlugin == null || jarPlugin.getVersion() == null) {
            log.info("auto-valhalla: could not determine the maven-jar-plugin "
                    + "version; cannot check whether it handles multi-release jars");
            return;
        }
        if (versionTooLow(jarPlugin.getVersion())) {
            log.warn("auto-valhalla: maven-jar-plugin " + jarPlugin.getVersion()
                    + " is in use; versions before 3.4.0 do not turn a jar with "
                    + "META-INF/versions entries into a multi-release jar on their "
                    + "own. Consider upgrading maven-jar-plugin to 3.4.0 or newer.");
        }
    }

    /**
     * True when {@code version} is older than {@link #JAR_PLUGIN_MRJ_VERSION}.
     * Compares the dot-separated numeric prefix; qualifier tokens (as in
     * {@code 3.4.0-beta}) are ignored. Versions that do not start with a
     * number never warn.
     */
    static boolean versionTooLow(String version) {
        String[] tokens = version.split("[.-]");
        if (tokens.length == 0 || parseToken(tokens[0]) < 0) {
            return false;
        }
        for (int i = 0; i < JAR_PLUGIN_MRJ_VERSION.length; i++) {
            int value = i < tokens.length ? parseToken(tokens[i]) : 0;
            if (value != JAR_PLUGIN_MRJ_VERSION[i]) {
                return value < JAR_PLUGIN_MRJ_VERSION[i];
            }
        }
        return false;
    }

    /** Parses one numeric version token; -1 when it is not a plain number. */
    private static int parseToken(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
