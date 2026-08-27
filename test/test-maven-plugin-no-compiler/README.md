# test-maven-plugin-no-compiler

Verifies the `auto-valhalla-maven-plugin` works when **no
`maven-compiler-plugin` is explicitly declared**. The base classes are
compiled by the default lifecycle binding, controlled purely via
`<maven.compiler.release>8</maven.compiler.release>` properties.

The test asserts the same multi-release structure: versioned class is a
value class (major 72), base class is identity (major 52).
