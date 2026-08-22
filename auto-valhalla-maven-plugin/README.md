![stable](https://img.shields.io/badge/stability-experimental-orange.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.thunkware/auto-valhalla-maven-plugin)](https://central.sonatype.com/artifact/io.github.thunkware/auto-valhalla-maven-plugin)

# auto-valhalla maven plugin

Compiles the value-class variants of your selected classes at build time and
packages them into a multi-release jar. Your classes stay ordinary identity
classes (usable on any JDK), while JDK 28+ loads the value-class variants from
`META-INF/versions/28`.

For the runtime javaagent alternative, see the
[auto-valhalla-agent project](../auto-valhalla-agent). For an
overview of the whole project, see the [auto-valhalla README](../README.md).

## Quickstart

```xml
<plugin>
  <groupId>io.github.thunkware</groupId>
  <artifactId>auto-valhalla-maven-plugin</artifactId>
  <version>0.1.1-SNAPSHOT</version>
  <executions>
    <execution>
      <goals>
        <goal>transform</goal>
        <goal>jar</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

Select classes in source with the
[`@AutoValhalla` annotation](../auto-valhalla-api#quickstart); the plugin converts
exactly the annotated top-level classes and nothing else:

The plugin is also available directly from the command line:

```bash
mvn auto-valhalla:transform auto-valhalla:jar
```

At runtime, run on JDK 28 with `--enable-preview`:

```bash
java --enable-preview -jar myapp.jar
```

## Goals

| Goal | Default phase | Description |
| --- | --- | --- |
| `transform` | `process-classes` | Runs the `auto-valhalla-processor` (via `javac -proc:only`) over the project's sources, selects the `@AutoValhalla` classes, and compiles the generated `value class`/`value record` copies with `javac --release 28 --enable-preview`, writing them under `META-INF/versions/28`. |
| `process-sources` | `process-sources` | Runs only the annotation processor: selects the `@AutoValhalla` classes and generates their copies under `target/auto-valhalla-generated-sources/selected` (with a `selection.txt` manifest). Nothing is compiled and nothing is written to the output directory — useful to inspect or post-process what would be transformed. |
| `jar` | `package` | Adds `Multi-Release: true` to the jar manifest so the JVM serves the value variants on JDK 28+ and the identity classes on older JDKs. |

## How it works

There is no bytecode rewriting anywhere: the `auto-valhalla-processor`
annotation processor (a dependency of this plugin, passed to `javac -
processorpath`) selects the top-level types that carry the `@AutoValhalla`
annotation, and writes generated copies of their source files into a generated dir
directory with the `class`/`record` declarations turned into
`value class`/`value record`. The `transform` goal then delegates to
the JDK compiler — `javac --release <N> --enable-preview` — which produces the
value-class files natively and enforces the value-class rules. The base classes
are left untouched, so they keep working on JDKs older than 28.

Because javac enforces the rules, an `@AutoValhalla` class that cannot be a
value class (a non-final class, a class with mutable fields, or one using
`synchronized`) fails the build by default instead of silently staying an
identity class. The base classes must therefore be compiled without preview
(`--release` lower than 28) — the value-class compilation itself is preview.

## Requirements

Run Maven itself on **JDK 28 or newer** — below 28 the goals log a warning and
leave everything as identity classes (the build never fails).

## Configuration

All parameters are optional:

| parameter | property | default | description |
| --- | --- | --- | --- |
| `versionDirectory` | `auto-valhalla.version` | `28` | the multi-release version directory, also the javac `--release` |
| `failOnAnnotationFailure` | — | `true` | fail the build when an `@AutoValhalla` class cannot be compiled as a value class |
| `javac` | `auto-valhalla.javac` | `JAVA28_HOME/bin/javac` on JDK 8–27, else `<java.home>/bin/javac` on JDK 28 | override the JDK compiler executable; Maven may run on JDK 8 through 27 only when `JAVA28_HOME` points to JDK 28 |
| `fork` | `auto-valhalla.fork` | `true` | run javac as a forked process; when `false`, compile in-process through the `javax.tools.JavaCompiler` API (the JDK running Maven is used and the `javac` override is ignored) |
| `skipProcessor` | `auto-valhalla.skipProcessor` | `false` | (`transform` only) skip the annotation-processor pass and compile the generated dir left by a previous `process-sources` run (or generated manually under `target/auto-valhalla-generated-sources/selected`) |
| `skip` | `auto-valhalla.skip` | `false` | skip both goals |
| `encoding` | `auto-valhalla.encoding` | `${project.build.sourceEncoding}` | character encoding for source compilation |
| `parameters` | `auto-valhalla.parameters` | inherited | generate metadata for reflection on method parameters (`-parameters`) |
| `debug` | `auto-valhalla.debug` | inherited | include debugging information (`-g` or `-g:none`) |
| `debuglevel` | `auto-valhalla.debuglevel` | inherited | keyword list for `-g:` (e.g. `lines,vars,source`) |
| `showWarnings` | `auto-valhalla.showWarnings` | inherited | show compiler warnings (passes `-nowarn` when `false`) |
| `showDeprecation` | `auto-valhalla.showDeprecation` | inherited | show deprecation warnings (`-deprecation`) |
| `compilerArgs` | — | inherited | list of additional arguments to pass to javac (e.g. `<compilerArgs><arg>-parameters</arg></compilerArgs>`) |
| `compilerArgument` | `auto-valhalla.compilerArgument` | inherited | single additional argument string to pass to javac |
| `maven-compiler` | — | — | nested configuration block (`<maven-compiler>` or `<compiler>`) containing any of the compiler options above |

Compiler configuration options (`parameters`, `debug`, `debuglevel`, `showWarnings`, `showDeprecation`, `encoding`, `compilerArgs`) can be specified directly on `<configuration>`, nested inside a `<maven-compiler>` (or `<compiler>`) block, or automatically inherited from the project's `maven-compiler-plugin` configuration:

```xml
<configuration>
  <!-- Nested compiler options -->
  <maven-compiler>
    <debug>true</debug>
    <parameters>true</parameters>
    <compilerArgs>
      <arg>-parameters</arg>
      <arg>-Xlint:all</arg>
    </compilerArgs>
  </maven-compiler>
</configuration>
```

Selection is by the `@AutoValhalla` annotation alone: an annotated class that
javac rejects (because it cannot be a value class) fails the build by default
(`failOnAnnotationFailure=true`).

## Example

The `test/test-maven-plugin` project binds both goals and builds a
runnable multi-release jar. The `PluginOutputTest` inspects the produced class
files with `javap` (base classes class-file 61, versioned variants class-file
72 / value classes) and `build.sh` runs the jar on JDK 28 to prove the value
classes are active at runtime.
