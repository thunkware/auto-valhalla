# auto-valhalla

Automatically turn your plain classes/records into value classes/records. 
Codes like a class on older JDKs, works like an int on Valhalla.

auto-valhalla is a Java agent that rewrites eligible identity classes into value
classes at class-load time, so existing code (compiled on older JDKs or even JDK28) 
transparently gets benefits value objects when you run on Valhalla-enabled JVM.

Background: Project Valhalla JEP 401 (https://openjdk.org/jeps/401).

## Quick start

### 1. Opt in with the annotation

Add `auto-valhalla-annotation` dependency and annotate your plain identity class:

```java
import io.github.thunkware.auto.valhalla.AutoValhalla;

@AutoValhalla
public class Point {
    public final int x;
    public final int y;
    public Point(int x, int y) { 
        this.x = x; 
        this.y = y; 
    }
}
```

Then launch with the agent:

```bash
java --enable-preview \
     -javaagent:auto-valhalla.jar \
     -jar myapp.jar
```

After transformation, your class behaves like a value class: `Objects.hasIdentity(new Point(1, 2))` returns `false`.

Because `@AutoValhalla` annotation was compiled with Java 5, it is
compatible **JDK 1.5 and later**. You can annotate classes in older codebases
without raising their JDK compile version.

### 2. Select by package or class with `includes`

Use `-Dauto-valhalla.includes` to convert classes if you cannot or do not want to edit
their source code. A value ending in `.` matches a package prefix; otherwise it is an
exact class name:

```bash
java --enable-preview \
     -Dauto-valhalla.includes=com.example. \
     -javaagent:auto-valhalla.jar \
     -jar myapp.jar
```

### 3. Convert everything with `includes=*`

Selection always happens through the `@AutoValhalla` annotation or `includes`.
The `mode` option then narrows which of those selected classes are actually
converted. Modes are comma-separated and case-insensitive (`-`, `_` and camelCase
accepted):

- `safe` — keep only selected classes that are *already `final`*. Non-final
  candidates are skipped, because converting them would break their subclasses.
- `ignore-non-final` — also convert non-final candidates. **Only use this when
  you are sure nothing subclasses them**, since converted classes are made
  `final`.
- `ignore-synchronized` — allow candidates with synchronized instance methods
  (their `ACC_SYNCHRONIZED` is stripped so they can become value classes).

The mode defaults to `yolo` which is same as `ignore-non-final,ignore-synchronized`. 
To convert every structurally suitable class, select everything with the `*` include
and (optionally) narrow with `mode`:

```bash
java --enable-preview \
     -Dauto-valhalla.includes='*' \
     -Dauto-valhalla.mode=safe \
     -javaagent:auto-valhalla.jar \
     -jar myapp.jar
```

> **Warning:** a converted class becomes `final` and loses identity — `==`
> becomes value equality and `System.identityHashCode`, `synchronized`,
> `WeakReference`, and `IdentityHashMap` behave differently. This is especially
> dangerous when converting everything, which happens without annotating
> anything.

## Options

Flags are supplied as **agent arguments** (`-javaagent:auto-valhalla.jar=...`),
**system properties**, or **environment variables** (in that order of
precedence). Within the agent-argument list, later options override earlier ones,
and a `.config` file is expanded in place (see below).

Canonical form uses the `auto-valhalla.` prefix; agent arguments may also use the
unprefixed name (e.g. `mode`). Environment variables map to the `AUTO_VALHALLA_*`
form (e.g. `auto-valhalla.includes` → `AUTO_VALHALLA_INCLUDES`).

| Option | Env var | Description |
| --- | --- | --- |
| `auto-valhalla.includes` | `AUTO_VALHALLA_INCLUDES` | Comma-separated classes/packages to convert. `*` matches everything; `foo.*` / `foo.` is a package prefix; a value containing a dot is an exact class name; a bare word (no dot) is also a package prefix. |
| `auto-valhalla.excludes` | `AUTO_VALHALLA_EXCLUDES` | Same matching rules, but never convert matching classes (wins over `includes` and the annotation). |
| `auto-valhalla.includes-file` | `AUTO_VALHALLA_INCLUDES_FILE` | Path to a file with one pattern per line. Blank lines and `#` comments are ignored. |
| `auto-valhalla.excludes-file` | `AUTO_VALHALLA_EXCLUDES_FILE` | As above, for excludes. |
| `auto-valhalla.mode` | `AUTO_VALHALLA_MODE` | Comma-separated set of modes that narrow which selected (annotated/included) classes convert. See the mode table below. |
| `auto-valhalla.debug` | `AUTO_VALHALLA_DEBUG` | `true` for verbose logging of selection decisions. |
| `auto-valhalla.on-fail-throw` | `AUTO_VALHALLA_ON_FAIL_THROW` | `true` to surface a loud `LinkageError` (a `ClassFormatError` at load) when a selected class cannot be safely transformed, instead of silently keeping it an identity class. |
| `auto-valhalla.on-fail-append-to` | `AUTO_VALHALLA_ON_FAIL_APPEND_TO` | Path to a file; the internal name of each selected class that fails to transform is appended (the file is created if it does not exist). |
| `auto-valhalla.config` | `AUTO_VALHALLA_CONFIG` | Path to a Java properties file supplying the options above (keys may omit the `auto-valhalla.` prefix). |

#### `mode` values

Tokens are case-insensitive and may use `-`, `_` or camelCase. The default mode
set is `ignore-non-final,ignore-synchronized`.

| Mode | Effect |
| --- | --- |
| `safe` | Keep only selected classes that are *already `final`*. Non-final candidates are skipped, because converting them would break their subclasses. |
| `ignore-non-final` | Also convert non-final candidates. They are made `final`, so only opt in when nothing subclasses them (otherwise subclasses fail with `IncompatibleClassChangeError`). |
| `ignore-synchronized` | Allow candidates with synchronized instance methods; their `ACC_SYNCHRONIZED` is stripped so they can become value classes. |
| `yolo` | Shorthand for `ignore-non-final,ignore-synchronized` (the default). |

### `.config` precedence

When `.config` is expanded, its entries are placed at that position in the option
stream. Therefore:

- if `.config` appears **first**, later CLI options override it;
- if CLI options appear **first** and `.config` later, the file overrides them.

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
on-fail-throw=true
```

### Feedback loop: `.on-fail-append-to` + `.excludes-file`

`on-fail-append-to` and `.excludes-file` are designed to work together. Run once
with `on-fail-throw` disabled and `on-fail-append-to` pointing at a file; every class
that could not be safely transformed is recorded there. Feed that file back as
`.excludes-file` on subsequent runs so those classes are skipped instead of
surfacing errors:

```bash
# first pass: record anything that fails
-Dauto-valhalla.includes=com.example. \
-Dauto-valhalla.on-fail-append-to=/var/tmp/auto-valhalla-failures.txt

# later passes: skip the classes that failed before
-Dauto-valhalla.includes=com.example. \
-Dauto-valhalla.excludes-file=/var/tmp/auto-valhalla-failures.txt
```

## Notes & limitations

- Classes that fail verification after rewriting are, by default, left as identity
  classes. Use `on-fail-throw` to make such cases fail with an exception.
- The agent rewrites identity records and final classes only. It never transforms
  JDK/system classes or its own support classes. Non-final classes are converted
  only with `mode=ignore-non-final` (or via `@AutoValhalla`/`includes`); in that
  case the class is made final and any existing subclasses will fail to load with
  an `IncompatibleClassChangeError`.
- **A converted class is `final`.** If anything subclasses it — even outside
  `mode`'s reach — that subclass will stop loading.
- **Semantics change.** For a converted class, `==` becomes value equality (two
  instances with equal fields compare `==`), `equals`/`hashCode` of the two
  fields, `synchronized` methods no longer take a monitor, and
  `System.identityHashCode`, `WeakReference`, and `IdentityHashMap` no longer see
  per-instance identity. A value class has no identity, so identity-keyed caches
  and `==`-based deduplication silently change behavior. **This is especially
  dangerous with `mode`**, which converts classes without annotating them.
- **Safe to attach anywhere.** The agent's entry point is a JDK 5 class file, so
  the jar loads on any JVM from JDK 5 up. On a JVM older than JDK 28 (or on JDK 28
  without `--enable-preview`) the agent prints a single warning and does nothing —
  your classes keep their original (identity) behavior and the application runs
  unchanged. There is no need to guard its use behind a JVM-version check.
- An already-loaded identity class cannot be retroactively made a value class at
  runtime; classes loaded *after* the agent attaches (or from the start, when
  attached via `-javaagent`) are the ones rewritten.

## Note on AI assistance

This project was developed with the help of an AI coding agent
([opencode](https://opencode.ai)). Humans designed, directed, reviewed, edited the work.
The agent authored the bulk of the implementation, build configuration, and documentation.
