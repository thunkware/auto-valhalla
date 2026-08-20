![stable](https://img.shields.io/badge/stability-experimental-orange.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.thunkware/auto-valhalla-agent)](https://central.sonatype.com/artifact/io.github.thunkware/auto-valhalla-agent)
[![javadoc](https://javadoc.io/badge2/io.github.thunkware/auto-valhalla-agent/javadoc.svg)](https://javadoc.io/doc/io.github.thunkware/auto-valhalla-agent)

# auto-valhalla agent

A Java agent that rewrites eligible identity classes into
[JEP-401](https://openjdk.org/jeps/401) value classes at class-load time.
Existing code (compiled on older JDKs) transparently gets the memory and
performance benefits of value classes when run on a Valhalla-enabled JVM.

For the annotation used to opt in, see the
[auto-valhalla-api project](../auto-valhalla-api). For attaching the agent
without `-javaagent`, see the
[auto-valhalla-agent-attach project](../auto-valhalla-agent-attach). For
compiling the value-class variants at build time instead, see the
[auto-valhalla-maven-plugin](../auto-valhalla-maven-plugin). For an overview,
see the [auto-valhalla README](../README.md).

## Quickstart

Annotate the class (see the
[api quickstart](../auto-valhalla-api#quickstart)):

```java
import io.github.thunkware.auto.valhalla.api.AutoValhalla;

@AutoValhalla
public final class Point {
    public final int x;
    public final int y;
    public Point(int x, int y) { this.x = x; this.y = y; }
}
```

Download the agent and run on JDK 28:

```bash
wget -O auto-valhalla-agent.jar https://repo1.maven.org/maven2/io/github/thunkware/auto-valhalla-agent/0.1.0/auto-valhalla-agent-0.1.0.jar
java --enable-preview \
     -javaagent:auto-valhalla-agent.jar \
     -jar myapp.jar
```

After transformation, `Objects.hasIdentity(new Point(1, 2)) == false`, and the
class behaves like a value object.

## Selecting classes

Classes are selected for conversion in one of two ways — or both:

* **`@AutoValhalla` annotation** — explicit, whole-codebase opt-in; the
  recommended way for code you control.
* **`includes` patterns** — for code you cannot or do not want to edit:

  ```bash
  java --enable-preview \
       -Dauto-valhalla.includes=com.example.model,com.example.dto \
       -javaagent:auto-valhalla-agent.jar \
       -jar myapp.jar
  ```

`excludes` patterns are checked first and override both. A class selected by
both the annotation and `includes` is treated as annotation-selected only.

## Mode

The annotation and `includes`/`excludes` decide *which* classes are selected.
Mode further narrows *which* of those selected classes are actually converted,
based on deeper class analysis, and *how* they are converted. If a class is
selected but not convertible under the active mode, that is a failure; see
[Success and failure handling](#success-and-failure-handling).

| Option | Description |
| --- | --- |
| `auto-valhalla.annotation-mode` | Mode(s) applied to annotation-selected classes. Default: `safe`. |
| `auto-valhalla.includes-mode` | Mode(s) applied to includes-selected classes. Default: `safe`. |

Multiple modes are comma-separated and case-insensitive —
`mark-class-final`, `mark_class_final` and `markClassFinal` are the same.

| Mode | Effect |
| --- | --- |
| `safe` | Convert only classes that can safely be converted: `final` class with `final` instance fields. |
| `mark-class-final` | Allow non-final classes by marking the class `final`. Only opt in when nothing subclasses them (or subclasses fail to load). |
| `mark-fields-final` | Allow classes with non-`final` instance fields if those fields are written only once from a constructor. The fields are marked `final`. |
| `remove-synchronized` | Allow classes with synchronized instance methods by removing the `synchronized` modifier. |
| `yolo` | Shorthand for `mark-class-final,mark-fields-final,remove-synchronized`. |
| `synchronization-monitor` | Instead of converting, instrument selected classes to log which objects are synchronized on at runtime. See [Synchronization monitor](#synchronization-monitor). |

## Synchronization monitor

Before converting classes, it can be helpful to know whether they are used in
`synchronized` blocks — converting those classes to value classes would cause an
`IdentityException` at every synchronization site.

Run the application with the agent in `synchronization-monitor` mode:

```bash
java --enable-preview \
    -Dauto-valhalla.includes='*' \
    -Dauto-valhalla.includes-mode=synchronization-monitor \
    -javaagent:auto-valhalla-agent.jar \
    -jar myapp.jar
```

Instead of converting, this instruments all selected classes and logs the class
names that are synchronized on — to the console or to
`auto-valhalla.synchronization.txt`. Feed the file back as `excludes-files` so
those classes are skipped in a later run. This mode cannot be combined with
other modes.

| Option | Description |
| --- | --- |
| `auto-valhalla.synchronization-monitor.append-to` | File path. Default: `auto-valhalla.synchronization.txt`. |

## Options

All options are system properties (`-Dauto-valhalla.option=value`) and can also
be set as environment variables — see
[Configuring with Environment Variables](#configuring-with-environment-variables).

### Selection

| Option | Description |
| --- | --- |
| `auto-valhalla.includes` | Comma-separated classes/packages to convert. `*` matches everything. |
| `auto-valhalla.excludes` | Same matching rules, but for exclusion (overrides `includes` and the annotation). |
| `auto-valhalla.includes-files` | Path to a file with one pattern per line. Blank lines and `#` comments are ignored. |
| `auto-valhalla.excludes-files` | As above, for excludes. |

### Success and failure handling

Controlled via per-logger level overrides — see [Log levels](#log-levels).

- **rejected** — class was selected but did not meet the suitability requirements (e.g. not final, non-final fields).
- **fail** — class passed suitability checks but hit an unexpected error during transformation.
- **success** — class was selected, passed checks, and was successfully transformed to a value class.

| Logger name | Default level | Effect |
| --- | --- | --- |
| `auto-valhalla.annotation.success` | `info` | If annotation-selected classes are successfully transformed, treat as info. |
| `auto-valhalla.includes.success` | `info` | If includes-selected classes are successfully transformed, treat as info. |
| `auto-valhalla.annotation.rejected` | `fatal` | If annotation-selected classes are rejected, treat as fatal. |
| `auto-valhalla.annotation.fail` | `fatal` | If annotation-selected classes hit an unexpected transform error, treat as fatal. |
| `auto-valhalla.includes.rejected` | `debug` | If includes-selected classes are rejected, treat as debug. |
| `auto-valhalla.includes.fail` | `debug` | If includes-selected classes hit an unexpected transform error, treat as debug. |

`fatal` causes a class-load exception (the JVM rejects the class rather than
silently keeping an identity class). Any other level (`error`, `warning`,
`info`, `debug`, `trace`, `off`) leaves the class as an identity class and logs
at that level.

### Recording

Each `*-append-to` file is read once at start-up so names already present are
not re-appended, and a missing file is treated as empty.

| Option | Description |
| --- | --- |
| `auto-valhalla.annotation.on-success-append-to` | Appends the class name of each annotation-selected class that is successfully converted. |
| `auto-valhalla.includes.on-success-append-to` | Same, for includes-selected classes. |
| `auto-valhalla.annotation.on-fail-append-to` | Appends the class name of each annotation-selected class that fails to convert. |
| `auto-valhalla.includes.on-fail-append-to` | Same, for includes-selected classes. |

### Logging

| Option | Description |
| --- | --- |
| `auto-valhalla.logging` | Logging system: `simple` (default), `none`, `application`. See below. |
| `logging.level.<logger-name>` | Log level for the logger: `off`, `fatal`, `error`, `warning`, `info`, `debug`, `trace`. Default: `info`. |

* `simple` — logs to standard error; only INFO or higher is printed. This is
  the default.
* `none` — the agent logs nothing.
* `application` — redirects the agent's logs to the instrumented application's
  slf4j logger. Works best for simple one-jar applications that do not use
  multiple classloaders; Spring Boot apps are supported as well. The output can
  be further configured by the application's own logging configuration (e.g.
  logback.xml or log4j2.xml).

#### Log levels

Levels are controlled by `logging.level.<logger-name>=<level>` where level is
one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL, or OFF. FATAL causes the passed-in
or a new exception to be thrown. The root logger is `logging.level.root`.

Example:

```
logging.level.root=WARN

# Agent startup and configuration
logging.level.io.github.thunkware.auto.valhalla.AutoValhallaAgent28=INFO

# Log classes used in `synchronized` when synchronization-monitor mode is enabled
logging.level.auto-valhalla.synchronization-monitor=INFO

# Log performance stats every minute
logging.level.io.github.thunkware.auto.valhalla.Stats=DEBUG
```

### Config file

| Option | Description |
| --- | --- |
| `auto-valhalla.config` | Path to a Java properties file supplying any of the options. |

Config-file entries are applied first, so an environment variable or system
property set alongside the file overrides it — including `includes` and
`excludes`, which are replaced wholesale rather than merged. Each key is spelled
exactly as the system property it stands for; any other key is logged as a
warning and ignored.

```properties
auto-valhalla.includes=com.example
auto-valhalla.excludes=com.example.dto
logging.level.auto-valhalla.includes.rejected=FATAL
```

### Feedback loop

One or both of `includes.on-fail-append-to` and `synchronization-monitor.append-to`
are designed to work together with `excludes-files`.

Run once with the default `includes.rejected=debug` (quiet) and
`includes.on-fail-append-to` pointing at a file; every class that could not be
safely transformed is recorded in the file. Similarly, run in
`synchronization-monitor` mode to find classes used in synchronization blocks.
Feed the files back as `excludes-files` on subsequent runs so those classes are
skipped instead of surfacing errors:

```bash
# first pass: record anything that fails (includes.rejected defaults to debug)
-Dauto-valhalla.includes=com.example \
-Dauto-valhalla.includes.on-fail-append-to=/tmp/auto-valhalla-failures.txt

# second pass: record classes used in synchronization blocks
-Dauto-valhalla.includes=com.example \
-Dauto-valhalla.includes-mode=synchronization-monitor \
-Dauto-valhalla.synchronization-monitor.append-to=/tmp/auto-valhalla-synchronization.txt

# later passes: skip the classes that failed before
-Dauto-valhalla.includes=com.example \
-Dauto-valhalla.excludes-files=/tmp/auto-valhalla-failures.txt,/tmp/auto-valhalla-synchronization.txt
```

The companion `includes.on-success-append-to` records the classes that *were*
converted, handy for applying `@AutoValhalla` or for turning a broad `includes`
sweep into an explicit `includes-files` list.

## Configuring with Environment Variables

Any setting configurable as a system property can also be set with an
environment variable: uppercase the property name and replace `.` and `-` with
`_`. For example, `auto-valhalla.includes` becomes
`AUTO_VALHALLA_INCLUDES`, and `auto-valhalla.includes-mode` becomes
`AUTO_VALHALLA_INCLUDES_MODE`. System properties take precedence over
environment variables when both are set.

## How it works

The agent's entry point is a JDK 5 class file, so the agent jar loads on any JVM
from JDK 5 up. On a Valhalla-enabled JVM (JDK 28 with `--enable-preview`) it
installs a class-file transformer that parses every loaded class and rewrites
the selected ones as value classes; on any other JVM it prints a single warning
and does nothing, so your application keeps its original behaviour. There is no
need to guard its use behind a JVM-version check.

A transformed class has no identity: `==` becomes value equality, `equals`/
`hashCode` derive from the fields, `synchronized` methods no longer take a
monitor, and `System.identityHashCode`, `WeakReference`, and `IdentityHashMap`
no longer see per-instance identity. See [Notes](#notes) for the full semantics.

## Notes

### Selection

- If annotation-selected classes fail conversion, the class is rejected by
  default (`annotation.rejected` and `annotation.fail` default to `fatal`). Use
  `-Dlogging.level.auto-valhalla.annotation.rejected=warning` to log and
  continue instead.
- If includes-selected classes fail conversion, the failure is logged at
  `debug` by default (`includes.rejected` and `includes.fail` default to
  `debug`). Use `-Dlogging.level.auto-valhalla.includes.rejected=fatal` to fail
  loudly.

### Transformation

- A converted class is `final`. If anything subclasses it, that subclass will
  fail class loading.
- The agent rewrites identity records and final classes only. It never
  transforms JDK/system classes or its own support classes. Non-final classes
  are converted (as final) when the mode includes `mark-class-final`; any
  existing subclass then fails to load.
- Classes loaded *before* the agent starts (or attaches) are not rewritten;
  only classes loaded afterwards are.

### JVM spec conformance

- **Semantics change.** For a converted class, identity-keyed caches and
  `==`-based deduplication silently change behaviour. **This is especially
  dangerous with `includes-mode`, which converts classes without annotating
  them.**
- **Safe to use with any JDK.** On a JVM older than JDK 28 (or on JDK 28
  without `--enable-preview`) the agent prints a warning and does nothing.
- An already-loaded identity class cannot be retroactively made a value class
  at runtime.

### Performance overhead

The agent parses every loaded class that is not on an `excludes` pattern once
at class-load time and rewrites the selected ones — a small one-time CPU cost
(typically well under a millisecond per class) to class loading. Once a class is
loaded there is zero overhead in value-class-transformation mode. In
synchronization-monitor mode there is non-zero but negligible overhead to every
synchronization block in instrumented classes.

Turn these loggers to `DEBUG` to see per-class and total transform stats:

```
-Dlogging.level.io.github.thunkware.auto.valhalla.ValueClassTransformer=DEBUG
-Dlogging.level.io.github.thunkware.auto.valhalla.Stats=DEBUG
```

## JFR

JFR can also be used to find classes used in synchronization:

```bash
# record
java -XX:StartFlightRecording:filename=locks.jfr,settings=none,\
+jdk.JavaMonitorEnter#enabled=true,\
+jdk.JavaMonitorEnter#stackTrace=false,\
+jdk.JavaMonitorWait#enabled=true,\
+jdk.JavaMonitorWait#stackTrace=false\
     -jar myapp.jar

# print class names
jfr print --json --events jdk.JavaMonitorEnter locks.jfr | \
    jq '.recording.events.[].values.monitorClass.name' | \
    sort | uniq | tr -d '"' | tr '/' '.'
```

Because JFR is intended to find lock contention and samples, it misses many
classes used in synchronization. The agent's synchronization-monitor mode does
not sample but instruments all selected classes.