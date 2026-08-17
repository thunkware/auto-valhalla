# auto-valhalla

> Automatically turn your plain classes and records into value classes!
> Codes like a class on older JDKs, works like an int on Valhalla.

auto-valhalla is a Java agent that rewrites eligible identity classes into
value classes at class-load time, so existing code (compiled on older JDKs)
transparently gets the memory and performance benefits of value classes when
run on a Valhalla-enabled JVM.

See [Background](#background) for what this means and how it works.

## Table of contents

- [Quick start](#quick-start)
  - [1. Opt in with the annotation](#1-opt-in-with-the-annotation)
  - [2. Opt in with `includes` flag](#2-opt-in-with-includes-flag)
  - [3. Attach agent](#3-attach-agent)
- [Background](#background)
- [Synchronization monitor](#synchronization-monitor)
- [Options](#options)
- [Notes](#notes)

## Quick start

### 1. Opt in with the annotation

Add `auto-valhalla-api` dependency and annotate your plain identity
class or record:

```java
import io.github.thunkware.auto.valhalla.api.AutoValhalla;

@AutoValhalla
public final class Point {
    public final int x;
    public final int y;
    public Point(int x, int y) { 
        this.x = x; 
        this.y = y; 
    }
}

@AutoValhalla
public record Currency(String code) { }
```

Then download the agent and run the app on JDK28:

```bash
java --enable-preview \
     -javaagent:auto-valhalla.jar \
     -jar myapp.jar
```

After transformation, your class or record behaves like a value object:
  * `Objects.hasIdentity(new Point(1, 2)) == false`
  * `Objects.hasIdentity(new Currency("USD")) == false`

Because the `@AutoValhalla` annotation was compiled with Java 5, it is compatible with
**JDK 1.5 and later**. You can apply the annotation in older codebases
without raising their compile version to JDK28.

To detect errors earlier at build time, run AutoValhallaVerifier in a unit test:
```java
import io.github.thunkware.auto.valhalla.api.AutoValhallaVerifier;

@Test
void test() {
    AutoValhallaVerifier.verify(Point.class, Currency.class);
}
```

### 2. Opt in with `includes` flag

If you cannot or do not want to edit the source code, use `-Dauto-valhalla.includes` to convert to value classes.

```bash
java --enable-preview \
     -Dauto-valhalla.includes=com.example.model,com.example.dto \
     -javaagent:auto-valhalla.jar \
     -jar myapp.jar
```

### 3. Attach agent

For some apps, it may be more convenient to attach the agent, which does not require changing the app startup scripts.

Add `auto-valhalla-agent-attach` dependency, and activate it to your app's main class:

```java
public class Main {
    
    static {
        // init auto-valhalla agent as early as possible.
        // if running on Valhalla-enabled JVM (i.e. JDK28 with preview) ...
        if (AutoValhallaAttachAgent.isSupported()) {
            System.setProperty("auto-valhalla.includes", "com.example"); // set options as needed
            AutoValhallaAttachAgent.attach(); // ... then attach auto-valhalla agent
        }
    }
    
    private static final Logger logger = ...
    
    public static void main(String[] args) {
        ...
    }
}
```

<a id="background"></a>
## Background: Value Objects in Project Valhalla JEP 401

Taking a step back, you might ask why you would want to do this, and what exactly the benefits of value classes are. 

Value objects provide _flattening_ that allows for denser, more compact memory usage, and _scalarization_ that allows the
JVM to more easily and frequently stack-allocate objects.

Consider an array of `Point` objects (from the example above). Before conversion, the
array holds references to separately allocated identity objects, one heap object per element:

```text
+----------+
| Point[3] |
+----------+
| p0       | -----------------------> +-----------+
| p1       | -----> +-----------+     |   Point   |
| p2       | -> +   |-----------+     +-----------+
+----------+    |   |   Point   |     | x = 5     |
                |   +-----------+     | y = 6     |
                |   | x = 3     |     +-----------+
                |   | y = 4     |
                |   +-----------+
                |
                v
            +-----------+
            |   Point   |
            +-----------+
            | x = 1     |
            | y = 2     |
            +-----------+
```

One heap object per element: poor memory density (a pointer plus a separate
object for every point) and poor reference locality (objects scattered in
memory, so iteration jumps between cache lines).

After conversion, the elements are _flattened_: each `Point`'s `x` and `y` fields are
stored directly in the array, like primitives, in a single contiguous block of memory:

```text
+----------+
| Point[3] |
+----------+
| p0.x     |
| p0.y     |
| p1.x     |
| p1.y     |
| p2.x     |
| p2.y     |
+----------+
```

## Synchronization monitor

Before converting classes, it could be helpful to know if they are used in
`synchronized` blocks. Converting those classes to value classes would cause
`IdentityException` at every synchronization site.

To find which classes are synchronized on, run the application with the agent
in `synchronization-monitor` mode:

```bash
java --enable-preview \
    -Dauto-valhalla.includes='*' \
    -Dauto-valhalla.includes-mode=synchronization-monitor \
    -javaagent:auto-valhalla.jar \
    -jar myapp.jar
```

Instead of converting to value classes, this will instrument all classes 
(because of `includes='*'`) and log class names that are synchronized on.
See log output in the console or in `auto-valhalla.synchronization.txt`.

You can use the file to:
  - more confidently apply `@AutoValhalla` annotation to classes, or
  - feed back as `excludes-files` in a later run to avoid converting those classes.

This mode cannot be combined with other modes.

| Option | Description |
| --- | --- |
| `auto-valhalla.synchronization-monitor.append-to` | File path. Default: `auto-valhalla.synchronization.txt`. |

## Options

Flags are supplied via system properties (`-Dauto-valhalla.option=value`).
All options can also be set as environment variables — see
[Configuring with Environment Variables](#configuring-with-environment-variables).

Canonical form uses the `auto-valhalla.` prefix.

### Selection

Classes are selected for conversion by the `@AutoValhalla` annotation, by
`includes` patterns, or both. `excludes` patterns are checked first and
override both. A class selected by both the annotation and `includes` is
treated as annotation-selected.

| Option | Description |
| --- | --- |
| `auto-valhalla.includes` | Comma-separated classes/packages to convert. `*` matches everything. |
| `auto-valhalla.excludes` | Same matching rules, but for exclusion (overrides `includes` and the annotation). |
| `auto-valhalla.includes-files` | Path to a file with one pattern per line. Blank lines and `#` comments are ignored. |
| `auto-valhalla.excludes-files` | As above, for excludes. |

### Mode

The `@AutoValhalla` annotation and `includes`/`excludes` decide _which_
classes are selected based on very basic class information. Mode further narrows _which_ of those selected classes
are actually converted, based on deeper class definition, and _how_ they are converted. If a class is selected but not convertible
under the active mode, that is a failure; see [Success and failure handling](#success-and-failure-handling).

| Option | Description |
| --- | --- |
| `auto-valhalla.annotation-mode` | Mode(s) applied to annotation-selected classes. Default: `safe`. |
| `auto-valhalla.includes-mode` | Mode(s) applied to includes-selected classes. Default: `safe`. |

#### Mode values

| Mode | Effect                                                                                                                                                          |
| --- |-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `safe` | Convert only classes that can safely be converted: `final` class with `final` instance fields.                                                          |
| `remove-synchronized` | Allow classes with synchronized instance methods by removing `synchronized` modifier.                                                                           |
| `mark-class-final` | Allow non-final classes by marking the class `final`. Only opt in when nothing subclasses them (or subclasses fail to load).                                    |
| `mark-fields-final` | Allow classes with non-`final` instance fields if those fields written only once in a constructor. The fields are marked `final`.                               |
| `yolo` | Shorthand for `remove-synchronized,mark-class-final,mark-fields-final`.                                                                                         |
| `synchronization-monitor` | Instead of converting, instrument selected classes to log which objects are synchronized on at runtime. See [Synchronization Monitor](#synchronization-monitor) |

Multiple modes are comma-separated. Mode names are case-insensitive and
accept `-`, `_`, or camelCase (`mark-class-final`, `mark_class_final`, and
`markClassFinal` are all the same).

### Success and failure handling

Controlled via [per-logger level overrides](#log-levels).

- **rejected** — class was selected but did not meet suitability requirements (e.g. not final, non-final fields).
- **fail** — class passed suitability checks but hit an unexpected error during transformation.
- **success** — class was selected, passed checks, and was successfully transformed to a value class.

| Logger name | Default level | Effect                                                                            |
| --- | --- |-----------------------------------------------------------------------------------|
| `auto-valhalla.annotation.success` | `info` | If annotation-selected classes are successfully transformed, treat as info.       |
| `auto-valhalla.includes.success` | `info` | If includes-selected classes are successfully transformed, treat as info.         |
| `auto-valhalla.annotation.rejected` | `fatal` | If annotation-selected classes are rejected, treat as fatal.                      |
| `auto-valhalla.annotation.fail` | `fatal` | If annotation-selected classes hit an unexpected transform error, treat as fatal. |
| `auto-valhalla.includes.rejected` | `debug` | If includes-selected classes are rejected, treat as debug.                        |
| `auto-valhalla.includes.fail` | `debug` | If includes-selected classes hit an unexpected transform error, treat as debug.   |

`fatal` causes a class load exception (the JVM rejects the class rather than silently keeping an identity class).
Any other level (`error`, `warning`, `info`, `debug`, `trace`, `off`) leaves the class as an identity class and logs at that level.

### Recording

Each `*-append-to` file is read once at start-up so names already present
are not re-appended, and a missing file is treated as empty.

| Option | Description |
| --- | --- |
| `auto-valhalla.annotation.on-success-append-to` | Appends the class name of each annotation-selected class that is successfully converted. |
| `auto-valhalla.includes.on-success-append-to` | Same, for includes-selected classes. |
| `auto-valhalla.annotation.on-fail-append-to` | Appends the class name of each annotation-selected class that fails to convert. |
| `auto-valhalla.includes.on-fail-append-to` | Same, for includes-selected classes. |

### Logging

| Option | Description                                                                                            |
| --- |--------------------------------------------------------------------------------------------------------|
| `auto-valhalla.logging` | Logging system: `simple` (default), `none`, `application`. See below.                                  |
| `logging.level.<logger-name>` | Log level for the logger. `off`, `fatal`, `error`, `warning`, `info`, `debug`, `trace`. Default: `info` |

#### Logging system

`auto-valhalla.logging` controls the agent’s logging system. Three values are supported:
  * `simple`: The agent will print out its logs using the standard error stream. Only INFO or higher logs will be printed. This is the default Java agent logging system.
  * `none`: The agent will not log anything.
  * `application`: The agent will attempt to redirect its own logs to the instrumented application's slf4j logger. This 
   works the best for simple one-jar applications that do not use multiple classloaders; Spring Boot apps are supported 
   as well. The Java agent output logs can be further configured using the instrumented application's logging 
   configuration (e.g. logback.xml or log4j2.xml). Make sure to test that this logging system works for your application
   before running it in a production environment.

#### Log levels

Log levels can be controlled by `logging.level.<logger-name>=<level>` where level is one of TRACE, DEBUG, INFO, WARN, 
ERROR, FATAL, or OFF. FATAL will cause passed-in or a new exception to be thrown. The root logger can be configured by 
using logging.level.root.

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

Config file entries are applied first, so an environment variable or system
property set alongside the file overrides it — including `includes` and
`excludes`, which are replaced wholesale rather than merged.

Each key is spelled exactly as the system property it stands for — options keep
their `auto-valhalla.` prefix, and per-logger levels their `logging.level.`
prefix. Any other key is logged as a warning and ignored.

```properties
auto-valhalla.includes=com.example
auto-valhalla.excludes=com.example.dto
logging.level.auto-valhalla.includes.rejected=FATAL
```

### Feedback loop

One or both of `includes.on-fail-append-to` and `synchronization-monitor.append-to` are designed
to work together with `excludes-files`.

Run once with the default `includes.rejected=debug` (quiet) and `includes.on-fail-append-to` pointing at a file; every class that could not
be safely transformed is recorded in the file. Similarly, run in `synchronization-monitor` mode and append to the file
to find classes used in synchronization blocks.

Feed the files back as `excludes-files` on subsequent runs so those classes are skipped instead of
surfacing errors:

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
converted, which is handy for applying `@AutoValhalla` annotation or for turning a broad `includes` sweep into an
explicit `includes-files` list:

```bash
# record what a broad sweep actually converted
-Dauto-valhalla.includes='*' \
-Dauto-valhalla.includes-mode=safe \
-Dauto-valhalla.includes.on-success-append-to=/tmp/auto-valhalla-converted.txt
```

## Configuring with Environment Variables

In certain environments, configuring settings through environment variables
is often preferred. Any setting that can be configured using a system
property can also be set using an environment variable. To determine the
correct environment variable name for a system property:

1. Convert the system property name to uppercase.
2. Replace all `.` and `-` characters with `_`.

For example, `auto-valhalla.includes` converts to `AUTO_VALHALLA_INCLUDES`,
and `auto-valhalla.includes-mode` converts to `AUTO_VALHALLA_INCLUDES_MODE`.

System properties take precedence over environment variables when both are
set.

## Notes

### Selection

- If annotation-selected classes fail conversion, the class is rejected by default
  (`annotation.rejected` and `annotation.fail` default to `fatal`). Use
  `-Dlogging.level.auto-valhalla.annotation.rejected=warning` to log and continue instead.
- If includes-selected classes fail conversion, the failure is logged at
  `debug` by default (`includes.rejected` and `includes.fail` default to `debug`). Use
  `-Dlogging.level.auto-valhalla.includes.rejected=fatal` to fail loudly.

### Transformation

- A converted class is `final`. If anything subclasses it, that subclass
  will fail class loading.
- The agent rewrites identity records and final classes only. It never
  transforms JDK/system classes or its own support classes. Non-final classes
  are converted (as final) when the mode includes `mark-class-final`; any
  existing subclass then fails to load.

### JVM Spec Conformance

- **Semantics change.** For a converted class, `==` becomes value equality
  (two instances with equal fields compare `==`), `equals`/`hashCode` of the
  two fields, `synchronized` methods no longer take a monitor, and
  `System.identityHashCode`, `WeakReference`, and `IdentityHashMap` no longer
  see per-instance identity. A value class has no identity, so identity-keyed
  caches and `==`-based deduplication silently change behavior. **This is
  especially dangerous with `includes-mode`**, which converts classes without
  annotating them.
- **Safe to use with any JDK.** The agent's entry point is a JDK 5 class
  file, so the agent jar loads on any JVM from JDK 5 up. On a JVM older than
  JDK 28 (or on JDK 28 without `--enable-preview`) the agent prints a single
  warning and does nothing — your classes keep their original (identity)
  behavior and the application runs unchanged. There is no need to guard its
  use behind a JVM-version check.
- An already-loaded identity class cannot be retroactively made a value class
  at runtime; classes loaded *after* the agent attaches (or from the start,
  when attached via `-javaagent`) are the ones rewritten.

### Performance Overhead

#### Instrumentation Overhead

The agent parses every loaded class that is not on an `excludes` pattern once at
class-load time, and rewrites the selected ones. It adds a small one-time CPU cost
(typically well under a millisecond per class) to class loading.

In value-class-transformation mode, there is absolutely zero overhead once a class
is loaded. Transformed value classes behave exactly as JDK would compile and execute.

In synchronization monitor mode, there is non-zero but negligible overhead to every
synchronization block in instrumented classes.

Turn these loggers to `DEBUG` to see per-class transform timings and running totals
```
-Dlogging.level.io.github.thunkware.auto.valhalla.ValueClassTransformer=DEBUG
-Dlogging.level.io.github.thunkware.auto.valhalla.Stats=DEBUG
```
#### Attach Overhead and Compatibility

Compared to starting with the `-javaagent` option, <a href="#3-attach-agent">attaching the agent</a> can be a rather
computationally heavy operation. However, once attached, the same instrumentation overhead applies. 

More importantly, attach mechanism is not compatible with all JVMs. It is less reliable and is [frowned upon](https://openjdk.org/jeps/451).
Balance the convenience it provides with the downsides when choosing it. And make sure to test that it works for your app
before running it in a production environment.

### Synchronization

JFR can also be employed to find classes used in synchronization:

```bash
# record
java -XX:StartFlightRecording=filename=locks.jfr \
     -XX:FlightRecorderOptions=stackdepth=0 \
     -XX:StartFlightRecording:jdk.JavaMonitorEnter#threshold=0ms \
     -XX:StartFlightRecording:jdk.JavaMonitorWait#threshold=0ms \
     -jar myapp.jar

# print class names
jfr print --json --events jdk.JavaMonitorEnter locks.jfr | \
    jq '.recording.events.[].values.monitorClass.name' | \
    sort | uniq | tr -d '"' | tr '/' '.'
```

However, because JFR is intended to find lock contention and because it performs sampling, 
it will miss many classes used in synchronization. In comparison, this agent's
synchronization-monitor mode does not sample but instead instruments all selected classes
to attempt to find all classes used in synchronization.

## AI assistance

This project was vibe-coded with an AI coding agent. Humans designed,
directed, reviewed, and edited the work. The agent authored the bulk of the
implementation, build configuration, and documentation.
