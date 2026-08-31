[![Maven Central](https://img.shields.io/maven-central/v/io.github.thunkware/auto-valhalla-api)](https://central.sonatype.com/search?q=auto-valhalla&namespace=io.github.thunkware&sort=name)
[![javadoc](https://javadoc.io/badge2/io.github.thunkware/auto-valhalla-api/javadoc.svg)](https://javadoc.io/doc/io.github.thunkware/auto-valhalla-api)

# auto-valhalla

> Automatically turn your plain classes and records into value classes!
>
> Codes like a class on older JDKs, works like an int on Valhalla.

## What is auto-valhalla?

auto-valhalla turns eligible ordinary classes into [JEP-401](https://openjdk.org/jeps/401)  _value_ classes. Existing 
code (compiled for older JDKs) transparently gets the memory and performance
benefits of value classes when run on a Valhalla-enabled JVM, and stays an
ordinary class when run on older JVMs.

It is available as javaagent (in two variations), and as a build-time
plugin for Maven:

- [auto-valhalla-api](auto-valhalla-api) — `@AutoValhalla` annotation and `AutoValhallaVerifier`.
- [auto-valhalla-agent](auto-valhalla-agent) — javaagent that rewrites classes at load time, started with `-javaagent` flag.
- [auto-valhalla-agent-attach](auto-valhalla-agent-attach) — javaagent that rewrites classes at load time, started without `-javaagent` flag.
- [auto-valhalla-maven-plugin](auto-valhalla-maven-plugin) — generates and compiles the value-classes at build time.

## Quickstart

* **Maven Plugin** — see the [maven-plugin quickstart](auto-valhalla-maven-plugin#quickstart).

* **Startup javaagent** — see the [agent quickstart](auto-valhalla-agent#quickstart):

  ```bash
  java --enable-preview \
       -javaagent:auto-valhalla-agent.jar \
       -jar myapp.jar
  ```

* **Dynamic Attach javaagent** — see the [attach quickstart](auto-valhalla-agent-attach#quickstart).

## Background

Value objects provide _flattening_ (denser, more compact memory usage) and
_scalarization_ (the JVM stack-allocates more easily). Consider an array of
`Point` objects. Before conversion, the array holds references to separately
allocated identity objects, one heap object per element which leads to poor
memory density and poor reference locality:

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

After conversion, each `Point`'s fields are stored directly, flattened in the array, like
primitives, in a single contiguous block:

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

auto-valhalla can be applied in these ways (choose one):

* **At build time** — the [Maven plugin](auto-valhalla-maven-plugin) generates
  and compiles `value class` or `value record` to be packaged in a multi-release jar,
  so JDK 28+ uses the value variants and older JDKs use the original identity classes.
* **At load time** — the [agent](auto-valhalla-agent) rewrites the selected
  classes when the JVM loads them, so nothing in the build changes.
  * See also dynamic [attach agent](auto-valhalla-agent-attach)
  * For the full configuration reference (modes, logging, synchronization monitor, feedback loop),
     see the [agent documentation](auto-valhalla-agent#options).

## Why auto-valhalla?

* **Codes like a class, runs like an int.** Continue to maintain your codebase as is with little or no change. Let the 
  maven plugin or javaagent turn them into value classes where it matters.

* **Works on any JDK.**
  * The annotation is compatible with JDK5+.
  * The maven plugin is compatible with JDK8+. Provided with a JDK28, it will generate value classes for identity classes
    of your choice. Classes will then be arranged for creating a Multi-Release jar.
  * The javaagent compatible with JDK5+. On JDK 5 through 27, the agent disables itself. On JDK28 Valhalla JVM, it
    instruments identity classes of your choice into value classes.

* **Opt in, not opt out.** Convert exactly what you choose: 
  * maven plugin if you want to edit and re-compile the source code
  * javaagent if you do not want to edit or re-compile the source code

## Java version compatibility

The following table lists the compatible Java version for various auto-valhalla artifacts. Obviously, to get benefits
of Valhalla, you must run on a Valhalla-enabled JDK28+ JDK.

| Artifact                   | Compatible Java version |
|----------------------------|-------------------------|
| auto-valhalla-api          | 5+                      |
| auto-valhalla-maven-plugin | 8+                      |
| auto-valhalla-agent        | 5+                      |
| auto-valhalla-agent-attach | 5+                      |

## Troubleshooting

Run with these flags to view how JVM lays out memory for identity or value classes:
```
java -XX:+UnlockDiagnosticVMOptions \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+PrintInlineLayout \
  -XX:+PrintFlatArrayLayout \
  -XX:+PrintFieldLayout \
  --enable-preview
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for building from source and the module
layout.

## License

[Apache 2.0](LICENSE.txt)
