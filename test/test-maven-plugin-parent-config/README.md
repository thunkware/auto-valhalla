# test-maven-plugin-parent-config

Verifies the `auto-valhalla-maven-plugin` works when the
`maven-compiler-plugin` is configured in a **parent POM** and the child
only declares the auto-valhalla plugin.

## Structure

| Module | Purpose |
| --- | --- |
| `test-maven-plugin-parent-config` | Parent POM that declares `maven-compiler-plugin` with `source=8, target=8` |
| `consumer/` | Child that inherits compiler settings and only adds the auto-valhalla plugin |
