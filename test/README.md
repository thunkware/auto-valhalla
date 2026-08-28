# Test modules

End-to-end consumer projects that exercise the auto-valhalla agent and Maven
plugin from the outside, just as a real user would.

## Directory layout

| Directory | Purpose                                                                                      |
| --- |----------------------------------------------------------------------------------------------|
| `test-jdk5/` | JDK 5 bytecode: proves the agent handles legacy class files and is safe on pre-Valhalla JVMs |
| `test-jdk17/` | JDK 17 bytecode: cross-version integration tests, records, agent-attach                      |
| `test-maven-plugin-jdk8/` | JAVA_HOME set to JDK8                                                                        |
| `test-maven-plugin-jdk28/` | JAVA_HOME set to JDK28, with compile target at JDK8                                          |
| `test-maven-plugin-nested-compiler/` | Maven plugin with nested `<compiler>` configuration                                          |
| `test-maven-plugin-no-compiler/` | Maven plugin with no explicit `maven-compiler-plugin` declaration                            |
| `test-maven-plugin-parent-config/` | Maven plugin with compiler settings inherited from a parent POM                              |
| `test-maven-plugin-remove-annotation/` | Maven plugin with `removeAnnotation=true`: `@AutoValhalla` stripped from generated sources   |

## Running

```bash
./test/run-tests.sh         # cross-version demos (needs JAVA_HOME)
./test/run-tests.sh debug   # with verbose logging
```

Or run the full verification suite from the project root:

```bash
./build.sh
```
