![stable](https://img.shields.io/badge/stability-experimental-orange.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.thunkware/auto-valhalla-agent-attach)](https://central.sonatype.com/artifact/io.github.thunkware/auto-valhalla-agent-attach)

# auto-valhalla agent attach

Attaches the [auto-valhalla agent](../auto-valhalla-agent) to an
already-running JVM — no `-javaagent` flag needed in your startup scripts.

For the agent itself, see the
[auto-valhalla-agent project](../auto-valhalla-agent). For an overview of the
whole project, see the [auto-valhalla README](../README.md).

## Quickstart

Add the dependency and call `AutoValhallaAttachAgent.attach()` as early as
possible at startup — e.g. from a static initializer of the main class:

```xml
<dependency>
  <groupId>io.github.thunkware</groupId>
  <artifactId>auto-valhalla-agent-attach</artifactId>
  <version>0.1.1-SNAPSHOT</version>
</dependency>
```

```java
import io.github.thunkware.auto.valhalla.AutoValhallaAttachAgent;

public class Main {

    // Attach the agent as early as possible.
    static {
        // On JDK 28+ with --enable-preview ...
        if (AutoValhallaAttachAgent.isSupported()) {
            System.setProperty("auto-valhalla.includes", "com.example"); // options as needed
            AutoValhallaAttachAgent.attach(); // ... then attach the agent
        }
    }

    public static void main(String[] args) {
        // ...
    }
}
```

`isSupported()` returns `true` only on a JVM that can transform classes
(JDK 28+ launched with `--enable-preview`); `attach()` throws
`IllegalStateException` otherwise, so guard the call with `isSupported()`.

## How it works

The `auto-valhalla-agent-attach` jar bundles a relocated copy of Byte Buddy on
top of the regular `auto-valhalla-agent` dependency. At attach time it installs
Byte Buddy's agent, appends the agent jar to the bootstrap classpath, and
installs the value-class transformer.

Because Byte Buddy is relocated into
`io.github.thunkware.auto.valhalla.internal.bytebuddy`, it never leaks onto your
application classpath. The jar contains no own sources — it ships
MANIFEST-only `-sources` and `-javadoc` placeholders for Maven Central.

## Compatibility and caveats

- Only classes loaded *after* `attach()` are rewritten — call it as early as
  possible so application classes are still upcoming.
- Attach is a heavier operation than starting with `-javaagent`, and it is not
  compatible with all JVMs. It is also [frowned upon](https://openjdk.org/jeps/451)
  by the JDK. Prefer `-javaagent` when you control the startup scripts, and
  test that attach works for your application before using it in production.

Compare the overhead and compatibility trade-offs in the
[agent documentation](../auto-valhalla-agent#performance-overhead).