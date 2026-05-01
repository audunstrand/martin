# Martin

A CLI tool for semantically-correct Kotlin refactorings. Martin uses the Kotlin compiler frontend for full type resolution, so structural changes are safe and all references are updated across the entire project.

Built to be used by both humans and AI coding agents.

## Prerequisites

- Java 21+

## Build

```bash
./gradlew shadowJar
```

The fat JAR is produced at `build/libs/martin-0.1.0.jar`. For convenience:

```bash
alias martin="java -jar /path/to/martin-0.1.0.jar"
```

## Usage

All commands take `--project, -p` to specify the Gradle project root.

### Rename a symbol

Renames a function, variable, class, or parameter and updates all references:

```bash
martin rename -p . -f src/main/kotlin/App.kt -l 5 -c 10 --new-name processItems
```

### Extract a function

Extracts a range of lines into a new function, automatically determining parameters and return values:

```bash
martin extract-function -p . -f src/main/kotlin/App.kt --start-line 10 --end-line 20 --name handleRequest
```

### Extract a variable

Extracts an expression at the cursor into a named `val`:

```bash
martin extract-variable -p . -f src/main/kotlin/App.kt -l 15 -c 20 --name baseUrl
```

### Inline a variable or function

Replaces all usages with the definition and removes the declaration:

```bash
martin inline -p . -f src/main/kotlin/App.kt -l 8 -c 9
```

## All Commands

| Command | Description |
|---|---|
| `rename` | Rename any symbol and update all references |
| `extract-function` | Extract lines into a new function |
| `extract-variable` | Extract an expression into a `val` |
| `inline` | Inline a variable or function |
| `move` | Move a declaration to a different package |
| `change-signature` | Add, remove, or reorder function parameters |
| `convert-to-expression-body` | Convert block body `{ return x }` to `= x` |
| `convert-to-block-body` | Convert expression body `= x` to `{ return x }` |
| `add-named-arguments` | Add explicit parameter names to call arguments |
| `extract-constant` | Extract a literal into a companion object constant |
| `safe-delete` | Delete a declaration only if it has no usages |
| `convert-property-to-function` | Convert a property to a function |
| `extract-parameter` | Extract a hardcoded value into a function parameter |
| `introduce-parameter-object` | Group parameters into a data class |
| `extract-interface` | Extract an interface from a class |
| `extract-superclass` | Extract a superclass from a class |
| `pull-up-method` | Move a method from a subclass to its superclass |
| `replace-constructor-with-factory` | Replace a constructor with a factory function |
| `convert-to-data-class` | Convert a class to a data class |
| `convert-to-extension-function` | Convert a method to an extension function |
| `convert-to-sealed-class` | Convert a class hierarchy to a sealed class |
| `encapsulate-field` | Make a public property private and generate accessors |
| `type-migration` | Change a type and update all related code |
| `move-statements-into-function` | Move statements into an existing function |

## Classpath Caching

Martin resolves your project's classpath via Gradle on first run and caches the result in `.martin/classpath.cache`. The cache auto-invalidates when `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, or `gradle/libs.versions.toml` change.

Add `.martin/` to your project's `.gitignore`.

## AI Agent Integration

Martin ships with a `SKILLS.md` file containing detailed command documentation formatted for AI agents. To copy it into your project:

```bash
martin init -p .
```

This writes `SKILLS.md` to your project root, which AI coding agents can read to learn how to use Martin for refactoring tasks.

## License

MIT
