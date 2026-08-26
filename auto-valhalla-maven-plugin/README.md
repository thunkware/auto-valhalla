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
        <goal>generate-sources</goal>
        <goal>compile-generated-sources</goal>
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
mvn auto-valhalla:generate-sources auto-valhalla:compile-generated-sources auto-valhalla:jar
```

At runtime, run on JDK 28 with `--enable-preview`:

```bash
java --enable-preview -jar myapp.jar
```

## Goals

| Goal | Default phase | Description |
| --- | --- | --- |
| `generate-sources` | `generate-sources` | Runs only the annotation processor: selects the `@AutoValhalla` classes and generates their copies under `target/auto-valhalla-generated-sources`. Nothing is compiled and nothing is written to the output directory — useful to inspect or post-process what would be transformed. |
| `compile-generated-sources` | `process-classes` | Compiles generated sources left by `generate-sources` under `target/auto-valhalla-generated-sources` into `META-INF/versions/28`. |
| `jar` | `package` | Adds `Multi-Release: true` to the jar manifest so the JVM serves the value variants on JDK 28+ and the identity classes on older JDKs. |

## How it works

There is no bytecode rewriting anywhere: the `auto-valhalla-processor`
annotation processor (a dependency of this plugin, passed to `javac -
processorpath`) selects the top-level types that carry the `@AutoValhalla`
annotation, and writes generated copies of their source files into a generated dir
directory with the `class`/`record` declarations turned into
`value class`/`value record`. The `compile-generated-sources` goal then delegates to
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
| `fork` | `auto-valhalla.fork` | `true` | run javac as a forked process; when `false`, compile in-process through the `javax.tools.JavaCompiler` API (the JDK running Maven is used and the `javac` override is ignored) |
| `skip` | `auto-valhalla.skip` | `false` | skip the goal |
| `parameters` | `auto-valhalla.parameters` | inherited | generate metadata for reflection on method parameters (`-parameters`) |
| `debug` | `auto-valhalla.debug` | inherited | include debugging information (`-g` or `-g:none`) |
| `debuglevel` | `auto-valhalla.debuglevel` | inherited | keyword list for `-g:` (e.g. `lines,vars,source`) |
| `showWarnings` | `auto-valhalla.showWarnings` | inherited | show compiler warnings (passes `-nowarn` when `false`) |
| `showDeprecation` | `auto-valhalla.showDeprecation` | inherited | show deprecation warnings (`-deprecation`) |
| `compilerArgs` | — | inherited | list of additional arguments to pass to javac (e.g. `<compilerArgs><arg>-parameters</arg></compilerArgs>`) |
| `compilerArgument` | `auto-valhalla.compilerArgument` | inherited | single additional argument string to pass to javac |
| `maven-compiler` | — | — | nested configuration block (`<maven-compiler>` or `<compiler>`) containing compiler-plugin options such as `executable` and `encoding`; options are also inherited from the project's `maven-compiler-plugin` declaration |

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
