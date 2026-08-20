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

Select classes with the [`@AutoValhalla` annotation](../auto-valhalla-api#quickstart)
or with `includes` patterns:

```xml
<configuration>
  <includes>
    <include>demo.Plain</include>
  </includes>
</configuration>
```

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
| `transform` | `process-classes` | Runs the `auto-valhalla-processor` (via `javac -proc:only`) over the project's sources, selects the `@AutoValhalla` classes and the classes matching `includes`, and compiles the adapted `value class`/`value record` copies with `javac --release 28 --enable-preview`, writing them under `META-INF/versions/28`. |
| `jar` | `package` | Adds `Multi-Release: true` to the jar manifest so the JVM serves the value variants on JDK 28+ and the identity classes on older JDKs. |

## How it works

There is no bytecode rewriting anywhere: the `auto-valhalla-processor`
annotation processor (a dependency of this plugin, passed to `javac -
processorpath`) selects the top-level types that carry the `@AutoValhalla`
annotation or match the `includes` patterns, and writes adapted copies of their
source files into a staging directory with the `class`/`record` declarations
turned into `value class`/`value record`. The `transform` goal then delegates to
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
| `includes` | `auto-valhalla.includes` | (empty) | patterns (dots or slashes) matching classes to convert, like `-Dauto-valhalla.includes` for the agent |
| `excludes` | `auto-valhalla.excludes` | (empty) | patterns never converted, checked first |
| `versionDirectory` | `auto-valhalla.version` | `28` | the multi-release version directory, also the javac `--release` |
| `failOnAnnotationFailure` | — | `true` | fail the build when an `@AutoValhalla` class cannot be compiled as a value class |
| `failOnIncludesFailure` | — | `false` | fail the build when an `includes`-selected class cannot be compiled as a value class |
| `javac` | `auto-valhalla.javac` | `<java.home>/bin/javac` | override the JDK compiler executable |
| `skip` | `auto-valhalla.skip` | `false` | skip both goals |

Selection mirrors the agent: `excludes` are checked first and override
everything; a class selected by both the annotation and `includes` is treated as
annotation-selected only; annotation failures fail the build by default while
includes failures are logged and skipped (`failOnIncludesFailure=false`).

## Example

The `test/auto-valhalla-demo-maven-plugin` project binds both goals and builds a
runnable multi-release jar. The `PluginOutputTest` inspects the produced class
files with `javap` (base classes class-file 61, versioned variants class-file
72 / value classes) and `build.sh` runs the jar on JDK 28 to prove the value
classes are active at runtime.