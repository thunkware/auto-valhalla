[![Maven Central](https://img.shields.io/maven-central/v/io.github.thunkware/auto-valhalla-agent-attach)](https://central.sonatype.com/artifact/io.github.thunkware/auto-valhalla-agent-attach)

# auto-valhalla agent attach

Dynamically attaches the [auto-valhalla agent](../auto-valhalla-agent) to an already-running JVM — no `-javaagent` flag needed in your
startup scripts.

For starting using `-javaagent` flag, see the [auto-valhalla-agent project](../auto-valhalla-agent).

For an overview of the whole project, see the [auto-valhalla README](../README.md).

## Quickstart

For some apps, it may be more convenient to attach the agent, which does not require adding `-javaagent` to app startup scripts.

Add the dependency and call `AutoValhallaAttachAgent.attach()` as early as
possible at startup — e.g. from a static initializer of the main class:

```xml
<dependency>
  <groupId>io.github.thunkware</groupId>
  <artifactId>auto-valhalla-agent-attach</artifactId>
  <version>0.2.0</version>
</dependency>
```

```java
import io.github.thunkware.auto.valhalla.AutoValhallaAttachAgent;

public class Main {

    // Attach the agent as early as possible.
    static {
        // On JDK28+ with --enable-preview ...
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

`isSupported()` returns `true` only on a JVM that can transform classes (JDK28+ launched with `--enable-preview`)

## Compatibility and caveats

- Only classes loaded *after* `attach()` are rewritten — call it as early as
  possible so application classes are still upcoming.
- Attach incurs a one-time computationally heavy cost, and it is not compatible with all JVMs. It is 
  also [frowned upon](https://openjdk.org/jeps/451) and is on a deprecation path. Prefer `-javaagent` when you control
  the startup scripts when possible. If you do use the dynamic attach agent, test that attach works for your application
  before using it in production.

Compare the overhead and compatibility trade-offs in the
[agent documentation](../auto-valhalla-agent#performance-overhead).
