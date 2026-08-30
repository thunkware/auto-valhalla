[![Maven Central](https://img.shields.io/maven-central/v/io.github.thunkware/auto-valhalla-maven-plugin)](https://central.sonatype.com/artifact/io.github.thunkware/auto-valhalla-maven-plugin)

# auto-valhalla maven plugin

For identity classes annotated with `@AutoValhalla`, the plugin
compiles them as value classes and packages them into a multi-release 
jar under `META-INF/versions/28`.

When running on older JDKs, your classes stay ordinary identity classes, 
while on Valhalla-enabled JDK28, your identity classes are loaded as value classes.

For the runtime javaagent alternative, see the
[auto-valhalla-agent project](../auto-valhalla-agent). For an
overview of the whole project, see the [auto-valhalla README](../README.md).

## Usage

There are two common setups.

### 1. Develop on an older JDK, deploy value classes on Valhalla JVMs

You typically work on older JDK and you want your code to take advantage of
value classes when run on a Valhalla-enabled JDK28.

Set `$JAVA_HOME` to your older JDK and `$JAVA28_HOME` to a JDK 28
installation. Then add the plugin to `pom.xml`:

```xml
<plugin>
  <groupId>io.github.thunkware</groupId>
  <artifactId>auto-valhalla-maven-plugin</artifactId>
  <version>0.2.0</version>
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
$JAVA28_HOME/bin/java --enable-preview -jar myapp.jar
```

### 2. Develop on JDK 28, target an older JDK for compatibility

In this less common scenario, you use JDK28 as your day-to-day `$JAVA_HOME` but 
want to compile your code for an older JDK, for widest possible compatibility.

Configure `maven-compiler-plugin` to target the older JDK (if not already):

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <release>17</release> <!-- or 8, 21, etc -->
  </configuration>
</plugin>
```

Then add the plugin:

```xml
<plugin>
  <groupId>io.github.thunkware</groupId>
  <artifactId>auto-valhalla-maven-plugin</artifactId>
  <version>0.2.0</version>
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

| Goal                             | Default phase           | Description                                                                                                       |
|----------------------------------|-------------------------|-------------------------------------------------------------------------------------------------------------------|
| `generate-sources`               | `generate-sources`      | Runs the annotation processor on main code, to generate code under `target/auto-valhalla-generated-sources`.      |
| `compile-generated-sources`      | `process-classes`       | Compiles generated sources left by `generate-sources` into `target/classes/META-INF/versions/28`.                                |
|                                  |                         |                                                                                                                   |
| `generate-test-sources`          | `generate-test-sources` | Runs the annotation processor on test code, to generate code under `target/auto-valhalla-generated-test-sources`. |
| `compile-generated-test-sources` | `process-test-classes`  | Compiles generated test sources left by `generate-test-sources` into `target/test-classes/META-INF/versions/28`.                      |

## How it works

There is no bytecode rewriting anywhere: the `auto-valhalla-processor`
annotation processor selects classes marked with `@AutoValhalla`
annotation, and generate source files with `value class`/`value record`. 
The `compile-generated-sources` goal then delegates to the JDK28 compiler — `javac --release 28 --enable-preview` — 
which produces the value-class files natively and enforces the value-class rules. 

Generated source is enabled only for JDK28. On older JDKs, they remain identity classes.

Because javac enforces the rules, an `@AutoValhalla` class that cannot be a
value class (a non-final class, a class with mutable fields, or one using
`synchronized`) fails the build instead of silently staying an identity class.

## Requirements

- **$JAVA_HOME=JDK28** — the plugin uses the JDK running Maven for the value-class
  compilation. No extra environment variables needed.
- **$JAVA_HOME=JDK8–27** — set `JAVA28_HOME` to a JDK 28 installation. The plugin
  uses that JDK's `javac` for the value-class compilation.

The `maven-jar-plugin` (≥ 3.4.0) handles the `Multi-Release: true` manifest
entry automatically when it detects `META-INF/versions` content. Older versions
trigger a warning from the plugin.

## Configuration

All parameters are optional:

| parameter | property | default | description                                                                                                 |
| --- | --- | --- |-------------------------------------------------------------------------------------------------------------|
| `compiler` | — | — | nested configuration block (`<compiler>`) containing compiler-plugin options such as `parameter` or `debug` |
| `configOrigin`| `auto-valhalla.config-origin` | `NESTED_FIRST` | origin of compiler config. NESTED_FIRST, PROJECT_FIRST, NESTED_ONLY, PROJECT_ONLY                           |
| `removeAnnotation` | `auto-valhalla.removeAnnotation` | `false` | remove the `@AutoValhalla` annotation from the generated source files.                                      |
| `skipGenerateSources` | `auto-valhalla.skipGenerateSources` | `false` | skip or disable generate-sources goal                                                                       |
| `skipCompileGeneratedSources` | `auto-valhalla.skipCompileGeneratedSources` | `false` | skip or disable compile-generated-sources goal                                                              |

Compiler configuration options can be specified
  * nested inside a `<compiler>` block, or
  * automatically inherited from the project's `maven-compiler-plugin` configuration

Nested compiler configuration overrides project compiler configuration. See also `configOrigin`. Some configurations
like `<release>` or `<enablePreview>` will always be forcibly set in particular ways when executing the plugin.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <release>17</release>
    <debug>true</debug>
    <parameters>true</parameters>
  </configuration>
</plugin>
<plugin>
  <groupId>io.github.thunkware</groupId>
  <artifactId>auto-valhalla-maven-plugin</artifactId>
  <version>0.2.0</version>
  <executions>
    <execution>
      <goals>
        <goal>generate-sources</goal>
        <goal>compile-generated-sources</goal>
      </goals>
    </execution>
  </executions>
  <configuration>
    <!-- nested compiler configuration. parameters can be any parameter accepted by maven-compiler-plugin. -->
    <!-- this configuration turns off debug but retains parameters=true from project's compiler configuration -->
    <compiler>
      <debug>false</debug>
    </compiler>
  </configuration>
</plugin>
```

### Includes/excludes

Unlike auto-valhalla-agent, the maven plugin does not have `includes` or `excludes` option. If you use the plugin to
edit and re-compile your source code, you should prefer the more robust and explicit option of applying or not applying
the annotation.

## Example

The `test/test-maven-plugin-jdk8` project binds both goals and builds a
runnable multi-release jar. The `PluginOutputTest` inspects the produced class
files with `javap` and `build.sh` runs the jar on JDK 28 to prove the value
classes are active at runtime.

Additional test modules verify other configurations:

| Module                                | What it tests                                                                                    |
|---------------------------------------|--------------------------------------------------------------------------------------------------|
| `test-maven-plugin-jdk8`              | JAVA_HOME at JDK8                                                                                |
| `test-maven-plugin-jdk28`             | JAVA_HOME at JDK28, with compile target at JDK8                                                  |
| `test-maven-plugin-no-compiler`       | No explicit `maven-compiler-plugin` declaration                                                  |
| `test-maven-plugin-nested-compiler`   | Nested `<compiler>` block with different settings                                                |
| `test-maven-plugin-parent-config`     | Compiler settings inherited from a parent POM                                                    |
| `test-maven-plugin-remove-annotation` | `removeAnnotation=true` strips the `@AutoValhalla` marker from the generated value-class sources |
