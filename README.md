# auto-valhalla

> Automatically turn your plain classes/records into value classes/records!
> Codes like a class on older JDKs, works like an int on Valhalla.

auto-valhalla is a Java agent that rewrites eligible identity classes into
value classes at class-load time, so existing code (compiled on older JDKs
or even JDK28) transparently gets value-object benefits when you run on a
Valhalla-enabled JVM.

Background: Project Valhalla JEP 401 (https://openjdk.org/jeps/401).

## Quick start

### 1. Opt in with the annotation

Add `auto-valhalla-annotation` dependency and annotate your plain identity
class or record:

```java
import io.github.thunkware.auto.valhalla.AutoValhalla;

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
public record Pair<T>(T first, T second) { }
```

Then download the agent and launch:

```bash
java --enable-preview \
     -javaagent:auto-valhalla.jar \
     -jar myapp.jar
```

After transformation, your class or record behaves like a value object:
  * `Objects.hasIdentity(new Point(1, 2)) == false`
  * `Objects.hasIdentity(new Pair(3, 4)) == false`

Because `@AutoValhalla` annotation was compiled with Java 5, it is compatible with
**JDK 1.5 and later**. You can apply the annotation in older codebases
without raising their compile version to JDK28.

### 2. Select by package or class with `includes`

Use `-Dauto-valhalla.includes` to convert classes if you cannot or do not
want to edit the source code. A setting ending in `.` matches a package
prefix; otherwise it is an exact class name:

```bash
java --enable-preview \
     -Dauto-valhalla.includes=com.example. \
     -javaagent:auto-valhalla.jar \
     -jar myapp.jar
```

## Synchronization monitor

Before converting classes, it would be helpful to know if they are used in
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

This will instrument all classes (because of `includes='*'`) and log class
names that are synchronized on. See log output in the console or in
`auto-valhalla.synchronization.txt`.

You can use the file to:
  - more confidently apply `@AutoValhalla` annotation to classes, or
  - feed back as `excludes-files` in a later run to avoid converting those classes.

| Option | Description |
| --- | --- |
| `auto-valhalla.synchronization-monitor.append-to` | File path. Default: `auto-valhalla.synchronization.txt`. |
| `auto-valhalla.synchronization-monitor.log-level` | Log level: `info` (default), `debug`, or `off`. |

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
| `auto-valhalla.includes` | Comma-separated classes/packages to convert. `*` matches everything; `foo.` is a package prefix; `foo.Bar` an exact class name. |
| `auto-valhalla.excludes` | Same matching rules, but for exclusion (overrides `includes` and the annotation). |
| `auto-valhalla.includes-files` | Path to a file with one pattern per line. Blank lines and `#` comments are ignored. |
| `auto-valhalla.excludes-files` | As above, for excludes. |

### Mode

The `@AutoValhalla` annotation and `includes`/`excludes` decide _which_
classes are selected based on very basic class information. Mode further narrows _which_ of those selected classes
are actually converted, based on deeper class definition, and _how_ they are converted. If a class is selected but not convertible
under the active mode, that is a failure; see [Failure handling](#failure-handling).

| Option | Description |
| --- | --- |
| `auto-valhalla.annotation-mode` | Mode(s) applied to annotation-selected classes. Default: `safe`. |
| `auto-valhalla.includes-mode` | Mode(s) applied to includes-selected classes. Default: `yolo`. |

#### Mode values

| Mode | Effect |
| --- | --- |
| `safe` | Convert only classes that are already `final`. Non-final candidates are not converted. |
| `remove-synchronized` | Allow candidates with synchronized instance methods; their `synchronized` modifier is removed. |
| `mark-class-final` | Also convert non-final candidates by marking the class `final`. Only opt in when nothing subclasses them (or subclasses fail to load). |
| `mark-fields-final` | If instance fields are non-`final` yet written only once in a constructor, mark them `final`. Candidates with a non-`final` field written elsewhere (or more than once) are rejected. |
| `yolo` | Shorthand for `remove-synchronized,mark-class-final,mark-fields-final`. The default for `includes-mode`. |
| `synchronization-monitor` | Instead of converting, instrument selected classes to log which objects are synchronized on at runtime. Optionally also records them to a file via `synchronization-monitor.append-to`. **Cannot be combined with other modes.** |

Multiple modes are comma-separated. Mode names are case-insensitive and
accept `-`, `_`, or camelCase (`mark-class-final`, `mark_class_final`, and
`markClassFinal` are all the same).

### Failure handling

Controls what happens when a selected class cannot be converted, and how
successful conversions are logged.

| Option | Description |
| --- | --- |
| `auto-valhalla.annotation.on-fail` | `throw` (default) surfaces a throwable; `error`, `warning`, `info`, `debug`, `off` log at that level and leave the class as an identity class. |
| `auto-valhalla.includes.on-fail` | Same, for includes-selected classes. Default: `debug`, so a broad sweep cannot crash the application. |
| `auto-valhalla.annotation.on-success` | Log level for a successful conversion: `info` (default), `debug`, or `off`. |
| `auto-valhalla.includes.on-success` | Same, for includes-selected classes. Default: `info`. |

### Recording

Each `*-append-to` file is read once at start-up so names already present
are not re-appended, and a missing file is treated as empty.

| Option | Description |
| --- | --- |
| `auto-valhalla.annotation.on-success-append-to` | Appends the class name of each annotation-selected class that is successfully converted. |
| `auto-valhalla.includes.on-success-append-to` | Same, for includes-selected classes. |
| `auto-valhalla.annotation.on-fail-append-to` | Appends the class name of each annotation-selected class that fails to convert. |
| `auto-valhalla.includes.on-fail-append-to` | Same, for includes-selected classes. |

### Diagnostics

| Option | Description |
| --- | --- |
| `auto-valhalla.log-level` | Logging verbosity: `off`, `error`, `warning`, `info`, `debug`. Default: `info`. |
| `auto-valhalla.logging` | Logging output mode: `simple` (default), `none`, `application`. See below. |

#### Logging modes

`simple` is the default and prints messages to stderr with a timestamp prefix.

`none` suppresses all agent logging.

`application` redirects agent logs to the instrumented application's SLF4J
logger (`io.github.thunkware.auto.valhalla`). The agent instruments two points
in the application's class loading to detect when the logging system is ready:

- **Non-Spring apps**: the bridge is installed once
  `org.slf4j.LoggerFactory.getILoggerFactory()` returns, which signals that
  SLF4J is initialized.
- **Spring Boot apps**: `SpringApplication`'s static initializer is detected
  first, which switches the trigger to
  `LoggingApplicationListener.initialize()` — the point at which Logback or
  Log4j2 is actually configured.

If SLF4J is not on the classpath the agent falls back to `simple` mode. Agent
startup messages emitted before the logging system is ready still go to stderr.

Use `application` when you want agent messages to flow through the same
logging framework as the rest of your application (including its log level
filtering, appenders, and structured output).

### Config file

| Option | Description |
| --- | --- |
| `auto-valhalla.config` | Path to a Java properties file supplying any of the options above. Keys may omit the `auto-valhalla.` prefix. |

When `auto-valhalla.config` is set, config file entries are applied after
env vars but can be overridden by explicit system properties set alongside it.

### Examples

```bash
# convert a whole package by prefix
-Dauto-valhalla.includes=com.example.

# convert only specific classes
-Dauto-valhalla.includes=com.example.Foo,com.example.Bar

# read options from a properties file (keys may omit auto-valhalla.)
-Dauto-valhalla.config=/etc/auto-valhalla.properties
```

`/etc/auto-valhalla.properties`:

```properties
includes=com.example.
excludes=com.example.dto.
annotation.on-fail=throw
```

### Feedback loop: `includes.on-fail-append-to` + `excludes-files`

`includes.on-fail-append-to` and `excludes-files` are designed to work
together. Run once with `includes.on-fail=debug` (the default) and
`includes.on-fail-append-to` pointing at a file; every class that could not
be safely transformed is recorded there. Feed that file back as
`excludes-files` on subsequent runs so those classes are skipped instead of
surfacing errors:

```bash
# first pass: record anything that fails
-Dauto-valhalla.includes=com.example. \
-Dauto-valhalla.includes.on-fail-append-to=/var/tmp/auto-valhalla-failures.txt

# later passes: skip the classes that failed before
-Dauto-valhalla.includes=com.example. \
-Dauto-valhalla.excludes-files=/var/tmp/auto-valhalla-failures.txt
```

The companion `includes.on-success-append-to` records the classes that *were*
converted, which is handy for turning a broad `includes` sweep into an
explicit `includes-files` list:

```bash
# record what a broad sweep actually converted
-Dauto-valhalla.includes='*' \
-Dauto-valhalla.includes-mode=safe \
-Dauto-valhalla.includes.on-success-append-to=/var/tmp/auto-valhalla-converted.txt
```

## Configuring with Environment Variables

In certain environments, configuring settings through environment variables
is often preferred. Any setting that can be configured using a system
property can also be set using an environment variable. To determine the
correct environment variable name for a system property:

1. Convert the system property name to uppercase.
2. Replace all `.` and `-` characters with `_`.

For example, `auto-valhalla.includes` converts to `AUTO_VALHALLA_INCLUDES`,
and `auto-valhalla.annotation.on-fail` converts to
`AUTO_VALHALLA_ANNOTATION_ON_FAIL`.

System properties take precedence over environment variables when both are
set.

## Notes & limitations

- If annotation-selected classes fail conversion, a `LinkageError` is thrown
  by default. Use `annotation.on-fail` to change behavior (e.g.
  `annotation.on-fail=warning` to log and continue).
- If includes-selected classes fail conversion, the failure is logged at
  `debug` by default. Use `includes.on-fail` to change behavior (e.g.
  `includes.on-fail=throw` to fail loudly).
- A converted class is `final`. If anything subclasses it, that subclass
  will fail class loading.
- The agent rewrites identity records and final classes only. It never
  transforms JDK/system classes or its own support classes. Non-final classes
  are converted (as final) when the mode includes `mark-class-final`; any
  existing subclass then fails to load.
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

## Note on AI assistance

This project was vibe-coded with an AI coding agent. Humans designed,
directed, reviewed, and edited the work. The agent authored the bulk of the
implementation, build configuration, and documentation.
