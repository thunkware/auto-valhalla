![stable](https://img.shields.io/badge/stability-experimental-orange.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.thunkware/auto-valhalla-processor)](https://central.sonatype.com/artifact/io.github.thunkware/auto-valhalla-processor)

# auto-valhalla processor

An annotation processor (`javac -processor`) that selects the top-level
`@AutoValhalla` classes and the classes matched by `includes` patterns in a
source tree and emits adapted copies with `value class`/`value record`
declarations, plus a `selection.txt` manifest describing what was selected.

The [auto-valhalla-maven-plugin](../auto-valhalla-maven-plugin) bundles this
module as a dependency, runs it with `javac -proc:only`, and then compiles the
staged adapted sources with `javac --release <N> --enable-preview` to produce
the multi-release value-class variants.

For the runtime javaagent alternative, see the
[auto-valhalla-agent project](../auto-valhalla-agent). For an overview of the
whole project, see the [auto-valhalla README](../README.md).

## What the processor does

Given a set of source files, it visits every top-level `class`/`record` and
applies the agent's selection semantics:

* `excludes` are checked first and override everything;
* a type selected by both `@AutoValhalla` and `includes` counts as
  annotation-selected only;
* `interface`/`enum`/`module-info`/`package-info` are never selected.

For each selected type it writes an adapted copy of its source file — with the
`value` keyword inserted before the `class`/`record` keyword — under the output
directory, preserving the package-relative layout. Several selected types in one
file are all adapted in a single copy. It then writes a `selection.txt` manifest
with one line per selected type:

```
ADAPTED annotated <qname> <relPath>
ADAPTED includes <qname> <relPath>
```

(I/O failures during selection are reported as `FAIL <bucket> <qname> <reason>`
instead.)

The processor never compiles anything. Turning the adapted sources into value
classes is the job of the enclosing build (the maven plugin) or your own javac
invocation with `--enable-preview`.

## Using it directly (without the maven plugin)

The plugin can drive it, but so can any build tool that calls javac (Gradle,
Ant, Bazel, `make`, scripts) — useful for non-Maven builds, for custom
multi-release jar packaging, or for a CI pre-flight gate that checks the
selection configuration. The processor is not published to Maven Central yet;
build it from this repository first (`mvn -pl auto-valhalla-processor -am
package`). The pass and the follow-up compile look like this:

```bash
# 1) select and stage the adapted sources
javac -proc:only \
  -processorpath auto-valhalla-processor.jar \
  -cp "$CLASSES:$COMPILE_DEPS" \
  -Aincludes=demo \
  -Aoutdir=staging \
  $(find src -name '*.java')

# 2) compile the adapted sources as value classes
javac --release 28 --enable-preview -proc:none \
  -cp "$CLASSES:$COMPILE_DEPS" \
  -d classes/META-INF/versions/28 \
  $(find staging -name '*.java')
```

Then package `classes` as a multi-release jar (`Multi-Release: true` in the
manifest) so JDK 28+ serves the value variants and older JDKs get the identity
classes.

### Options

| `-A` option | default | description                                                          |
| --- | --- |----------------------------------------------------------------------|
| `includes` | (empty) | Comma-separated classes/packages to convert. `*` matches everything. |
| `excludes` | (empty) | Same matching rules, but for exclusion (overrides `includes` and the annotation). |
| `outdir` | (required) | directory that receives the adapted sources and `selection.txt`      |

## Prerequisites

* To **run** the processor, the javac that loads it must be JDK 23 or newer
  (it uses the two-argument `SourcePositions` API). The value-class compilation
  it feeds needs JDK 28+ with `--enable-preview`, so in practice the whole
  pipeline requires JDK 28.
* The processor is compiled for Java 17 class files and has no dependencies
  (it does not even need `auto-valhalla-api` on its processor path — selection
  is done by matching the annotation's simple or fully qualified name).

## Requirements for the maven plugin

Nothing — the plugin resolves `auto-valhalla-processor` automatically as one of
its dependencies and hands its location to javac's `-processorpath`. See the
[plugin README](../auto-valhalla-maven-plugin) for the compile-time transform
and `jar` goals.
