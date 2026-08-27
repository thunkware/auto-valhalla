# test-maven-plugin-nested-compiler

Verifies the `auto-valhalla-maven-plugin` correctly honors a **nested
`<compiler>` configuration** block passed via the plugin's own
`<configuration>`. The value-class compilation uses different compiler
settings (`parameters=true`, `debug=false`, `-Xlint:all`) than the base
compilation (`parameters=false`, `debug=true`).

The test asserts the versioned class has `MethodParameters` (proving
`parameters=true`) and lacks `LineNumberTable` (proving `debug=false`).
