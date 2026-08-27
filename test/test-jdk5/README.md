# test-jdk5

Proves the agent handles **genuine Java 5 bytecode** (class-file version 49)
and is safe to load on a pre-Valhalla JVM.

## Submodules

| Module | Purpose |
| --- | --- |
| `test-lib-jdk5` | Value-class candidates compiled by a real JDK 5 `javac` (`-source 1.5 -target 1.5`) |
| `test-main-jdk5` | Runs on JDK 5 with `-javaagent` to verify the agent warns and returns without crashing |

## Requirements

`JAVA5_HOME` must point to a JDK 5 or 6 installation. When absent the
`skip-jdk5` profile disables both modules.

## Source classes

- `demo5.annotation.Point` &mdash; `@AutoValhalla` annotated, value-class candidate
- `demo5.includes.Square`, `demo5.includes.Circle` &mdash; selected by package prefix (`includes-mode=yolo`)
- `demo5.broken.MutablePoint`, `demo5.broken.SyncPoint` &mdash; annotated but invalid; the agent must reject them
