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

## Usage

There are two common setups.

### 1. Develop on an older JDK, deploy value classes on Valhalla JVMs

You work on JDK 8, 17, 21, or 25 and want your code to take advantage of
value classes when run on a Valhalla-enabled JVM.

Set `$JAVA_HOME` to your older JDK and `$JAVA28_HOME` to a JDK 28
installation, then add the plugin to your `pom.xml`:

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
      </goals>
    </execution>
  </executions>
</plugin>
```

Annotate the classes you want as value classes with
[`@AutoValhalla`](../auto-valhalla-api#quickstart). The plugin compiles them
as value classes and packages them into a multi-release jar under
`META-INF/versions/28`.

When run on an older JDK, all classes behave exactly as before. On a
Valhalla-enabled JVM (JDK 28+), the `@AutoValhalla` classes are loaded as
value classes:

```bash
java --enable-preview -jar myapp.jar
```

### 2. Develop on JDK 28, target an older JDK for compatibility

You use JDK 28 as your day-to-day `$JAVA_HOME` but want to compile for an
older JDK for widest possible compatibility.

Configure `maven-compiler-plugin` to target the older JDK (if not already):

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <release>17</release>
  </configuration>
</plugin>
```

Then add the plugin:

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
      </goals>
    </execution>
  </executions>
</plugin>
```

No `$JAVA28_HOME` or other JDK environment variables are needed — the
plugin uses the JDK running Maven directly.

The outcome is the same: `@AutoValhalla` classes are compiled as value
classes and built into a multi-release jar. Older JDKs load the identity
class variants; Valhalla JVMs load the value class variants.

### Command line

The plugin goals can also be invoked directly:

```bash
mvn auto-valhalla:generate-sources auto-valhalla:compile-generated-sources
```

## Goals

| Goal | Default phase | Description |
| --- | --- | --- |
| `generate-sources` | `generate-sources` | Runs only the annotation processor: selects the `@AutoValhalla` classes and generates their copies under `target/auto-valhalla-generated-sources`. Nothing is compiled and nothing is written to the project's output directory (`target/classes`) — useful to inspect or post-process what would be transformed. |
| `compile-generated-sources` | `process-classes` | Compiles generated sources left by `generate-sources` under `target/auto-valhalla-generated-sources` into `META-INF/versions/28`. |

## How it works

There is no bytecode rewriting anywhere: the `auto-valhalla-processor`
annotation processor selects the top-level types that carry the `@AutoValhalla`
annotation, and writes generated copies of their source files into a generated
directory with the `class`/`record` declarations turned into
`value class`/`value record`. The `compile-generated-sources` goal then delegates to
the JDK compiler — `javac --release 28 --enable-preview` — which produces the
value-class files natively and enforces the value-class rules. The base classes
are left untouched, so they keep working on JDKs older than 28.

Because javac enforces the rules, an `@AutoValhalla` class that cannot be a
value class (a non-final class, a class with mutable fields, or one using
`synchronized`) fails the build instead of silently staying an identity class.
The base classes must therefore be compiled without preview (`--release` lower
than 28) — the value-class compilation itself is preview.

## Requirements

- **JDK 28** — the plugin uses the JDK running Maven for the value-class
  compilation. No extra environment variables needed.
- **JDK 8–27** — set `JAVA28_HOME` to a JDK 28 installation. The plugin
  uses that JDK's `javac` for the value-class compilation.

The `maven-jar-plugin` (≥ 3.4.0) handles the `Multi-Release: true` manifest
entry automatically when it detects `META-INF/versions` content. Older versions
trigger a warning from the plugin.

## Configuration

All parameters are optional:

| parameter | property | default | description |
| --- | --- | --- | --- |
| `skipGenerateSources` | `auto-valhalla.skipGenerateSources` | `false` | skip the annotation-processor pass |
| `skipCompileGeneratedSources` | `auto-valhalla.skipCompileGeneratedSources` | `false` | skip the value-class compilation |
| `compiler` | — | — | nested configuration block (`<compiler>`) containing compiler-plugin options such as `executable` and `encoding` |

Compiler configuration options can be specified nested inside a `<compiler>` block, or
automatically inherited from the project's `maven-compiler-plugin` configuration:

```xml
<configuration>
  <!-- nested compiler configuration. parameters can any parameter accepted by maven-compiler-plugin -->
  <compiler>
    <debug>true</debug>
    <parameters>true</parameters>
    <compilerArgs>
      <arg>-parameters</arg>
      <arg>-Xlint:all</arg>
    </compilerArgs>
  </compiler>
</configuration>
```

## Example

The `test/test-maven-plugin-jdk8` project binds both goals and builds a
runnable multi-release jar. The `PluginOutputTest` inspects the produced class
files with `javap` and `build.sh` runs the jar on JDK 28 to prove the value
classes are active at runtime.

Additional test modules verify other configurations:

| Module                              | What it tests                                     |
|-------------------------------------|---------------------------------------------------|
| `test-maven-plugin-jdk25`           | Base classes at release 25                        |
| `test-maven-plugin-no-compiler`     | No explicit `maven-compiler-plugin` declaration   |
| `test-maven-plugin-nested-compiler` | Nested `<compiler>` block with different settings |
| `test-maven-plugin-parent-config`   | Compiler settings inherited from a parent POM     |
