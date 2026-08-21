# Contributing / Building from source

This document is for people working on `auto-valhalla` itself. End users should
read [README.md](README.md) instead.

## Modules

| Module | Purpose |
| --- | --- |
| `auto-valhalla-api` | The `@AutoValhalla` annotation and `AutoValhallaVerifier`. Compiled with a **real JDK 5** to genuine Java 5 bytecode (major 49) so it is loadable everywhere. |
| `auto-valhalla-agent` | The real agent (`AutoValhallaAgent28`, `ValueClassTransformer`, `ValueClassRewriter`, `ConstructorRewriter`). Compiled **without** preview so it loads even when preview is off; embeds the annotation and the JDK 5 shim (`AutoValhallaAgent`). |
| `test/test-5-lib` | A value-class candidate compiled to Java 5 bytecode (real JDK 5), proving the agent handles legacy class files. |
| `test/test-16-lib` | Candidates compiled to Java 16 bytecode (a record is included to show records are rewritten by the agent). |
| `test/test-16-main` | Runs the demos with `Objects.hasIdentity` to report value-ness, and holds the agent-attach integration test. |
| `test/test-5-main` | A JDK 5 app used to prove the agent is safe to attach on a pre-Valhalla JVM (it must only warn and return). |

## Building

The agent is built with Maven. Two JDK locations are required as environment
variables:

```bash
# set the JDK locations (export JAVA_HOME / JAVA5_HOME yourself)
export JAVA_HOME=/path/to/jdk-28
export JAVA5_HOME=/path/to/jdk-5-or-6
./build.sh
```

- `JAVA_HOME` — the JDK that compiles and runs Maven (JDK 28+).
- `JAVA5_HOME` — an old JDK (5/6) used, via `exec-maven-plugin`, to compile the
  shim (inside `auto-valhalla-agent`), the annotation, and `test-5-lib` to
  Java 5 bytecode (JDK 5's `javac -version` exits non-zero, so
  `maven-compiler-plugin` is not used for them), and to verify the agent is safe
  to attach on a pre-Valhalla JVM (`test-5-main`).

This produces `auto-valhalla-agent/target/auto-valhalla-agent-<version>.jar` — a single self-contained jar
that contains the JDK 28 real agent **and** a JDK 5 shim
(`AutoValhallaAgent`, major version 49) as its `Premain-Class`/`Agent-Class`.
On a JVM older than JDK 28 the shim warns and returns harmlessly; on JDK 28+ it
delegates to the real agent. The real agent checks
`RuntimeMXBean.getInputArguments()` for `--enable-preview`: if the JVM was not
started with it, the agent prints a warning and disables itself (leaving
classes as identity classes) instead of producing class files the JVM would
reject.

## Running the demos

```bash
./test/run-demo.sh          # both with and without the agent (needs JAVA_HOME set)
./test/run-demo.sh debug     # with verbose logging
```

Each demo class prints `hasIdentity(...)`; with the agent it becomes `false`
for converted classes.

## Tests

The agent-attach integration test (`test-16-main`'s `AgentAttachTest`)
attaches the built agent to the running JVM and asserts a subsequently-loaded
class is rewritten into a value class. It needs the agent jar to exist first, so
build with `mvn install` (or `mvn package`) before running the test:

```bash
mvn -DskipTests install
mvn -pl test/test-16-main test
```

The test is skipped automatically if
`auto-valhalla-agent/target/auto-valhalla-agent-<version>.jar` is missing.

`./build.sh` runs the full verification suite (build, jar audit, JDK 5 safety,
JDK 28 no-preview disable, the attach test, and the demos) and needs all three
JDK environment variables (JAVA_HOME, JAVA5_HOME) set.
