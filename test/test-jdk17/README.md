# test-jdk17

Cross-version integration tests using **JDK 17 bytecode**, including records.

## Submodules

| Module | Purpose |
| --- | --- |
| `test-lib-jdk17` | Value-class candidates compiled at `--release 16` (includes a Java record) |
| `test-main-jdk17` | Runs demos on JDK 28 preview with the agent; holds agent-attach integration tests |

## Source classes

- `demo16.annotation.Money` &mdash; `@AutoValhalla` annotated
- `demo16.includes.Hsl` &mdash; selected by package prefix (`demo16.includes.`)
- `demo16.includes.Pair` &mdash; a plain Java `record`, proving the agent rewrites records into value classes

## Tests

- **AgentAttachTest** &mdash; dynamically attaches the agent at runtime via `VirtualMachine.attach()` and asserts a subsequently loaded class becomes a value class
- **AttachViaSystemPropertyTest** &mdash; forks a JVM with `-Ddemo.attach=true` (no `-javaagent`) to prove the system-property self-attach path works
