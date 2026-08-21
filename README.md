![stable](https://img.shields.io/badge/stability-experimental-orange.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.thunkware/auto-valhalla-api)](https://central.sonatype.com/search?q=auto-valhalla&namespace=io.github.thunkware&sort=name)
[![javadoc](https://javadoc.io/badge2/io.github.thunkware/auto-valhalla-api/javadoc.svg)](https://javadoc.io/doc/io.github.thunkware/auto-valhalla-api)

# auto-valhalla

> Automatically turn your plain classes and records into value classes!
>
> Codes like a class on older JDKs, works like an int on Valhalla.

## What is auto-valhalla?

auto-valhalla turns eligible identity classes into
[JEP-401](https://openjdk.org/jeps/401) value classes — without rewriting your
application. Existing code (compiled on older JDKs) transparently gets the
memory and performance benefits of value classes when run on a Valhalla-enabled
JVM, and stays an ordinary class everywhere else.

It is available as a runtime javaagent, an attach helper, and a build-time
plugin for Maven:

- [auto-valhalla-api](auto-valhalla-api) — the `@AutoValhalla` annotation and `AutoValhallaVerifier`.
- [auto-valhalla-agent](auto-valhalla-agent) — the javaagent that rewrites classes at load time.
- [auto-valhalla-agent-attach](auto-valhalla-agent-attach) — attaches the agent to a running JVM without `-javaagent`.
- [auto-valhalla-maven-plugin](auto-valhalla-maven-plugin) — compiles the value-class variants into a multi-release jar at build time.

## Why auto-valhalla?

* **Codes like a class, runs like an int.** Write and test against ordinary
  identity classes, and let the agent (or the build-time plugins) turn them
  into value classes where it matters.

* **Works on any JDK.** The annotation is Java 5 bytecode and the agent
  self-disables on a non-Valhalla JVM with a single warning — your application
  runs unchanged on JDK 5 through 27, and gets the value-class benefits on
  JDK 28.

* **Opt in, not opt out.** Convert exactly what you choose: the `@AutoValhalla`
  annotation, `includes` patterns, or a build-time plugin — never everything at
  once.

* **Catches problems at build time.** `AutoValhallaVerifier` checks the
  structural prerequisites in a unit test, and the Maven plugin fails the
  build instead of silently keeping an identity class.

* **No bytecode rewriting.** The agent lets the JVM apply its own
  value-class rules; the build-time plugins generate your sources and let `javac`
  compile them natively.

## Quickstart

* **Annotation** — see the [api quickstart](auto-valhalla-api#quickstart).

* **Runtime javaagent** — see the [agent quickstart](auto-valhalla-agent#quickstart):

  ```bash
  java --enable-preview \
       -javaagent:auto-valhalla-agent.jar \
       -jar myapp.jar
  ```

* **Attach** — see the [attach quickstart](auto-valhalla-agent-attach#quickstart).

* **Maven** — see the [maven-plugin quickstart](auto-valhalla-maven-plugin#quickstart).

## How it works

Value objects provide _flattening_ (denser, more compact memory usage) and
_scalarization_ (the JVM stack-allocates more easily). Consider an array of
`Point` objects. Before conversion, the array holds references to separately
allocated identity objects, one heap object per element — poor memory density
and poor reference locality:

```text
+----------+          +-----------+     +-----------+
| Point[3] |----+     |   Point   |     |   Point   |
+----------+    |     +-----------+     +-----------+
| p0       |----+---->| x = 1     |     | x = 5     |
| p1       |--------->| y = 2     |     | y = 6     |
| p2       |--+       +-----------+     +-----------+
+----------+  |       +-----------+
              +------>|   Point   |
                      +-----------+
                      | x = 3     |
                      | y = 4     |
                      +-----------+
```

After conversion, each `Point`'s fields are stored directly in the array, like
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

auto-valhalla applies this in two ways:

* **At load time** — the [agent](auto-valhalla-agent) rewrites the selected
  classes when the JVM loads them, so nothing in the build changes.
* **At build time** — the [Maven plugin](auto-valhalla-maven-plugin) compiles
  generated `value class`/`value record` copies with `javac --release 28
  --enable-preview` and packages them as a multi-release jar, so JDK 28+ uses
  the value variants and older JDKs use the original identity classes.

For the full configuration reference (modes, logging, synchronization monitor,
feedback loop), see the [agent documentation](auto-valhalla-agent#options).

## Project structure

| Module | Purpose |
| --- | --- |
| [`auto-valhalla-api`](auto-valhalla-api) | The `@AutoValhalla` annotation and `AutoValhallaVerifier`. |
| [`auto-valhalla-agent`](auto-valhalla-agent) | The runtime javaagent. |
| [`auto-valhalla-agent-attach`](auto-valhalla-agent-attach) | Attach helper built on a relocated Byte Buddy. |
| [`auto-valhalla-maven-plugin`](auto-valhalla-maven-plugin) | Build-time multi-release jar plugin for Maven. |
| `test/` | Demos, the demo runner, and the e2e verification scripts. |

## Getting help

* Questions: open a [GitHub issue](https://github.com/thunkware/auto-valhalla/issues).
* Reporting bugs: file an issue with a minimal reproduction and the agent's
  debug logs (`-Dlogging.level.io.github.thunkware.auto.valhalla=DEBUG`).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for building from source and the module
layout.

## License

[Apache 2.0](LICENSE.txt)