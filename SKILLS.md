# Martin - Kotlin Refactoring CLI

## Overview

Martin is a CLI tool that performs semantically-correct refactorings on Kotlin codebases. It uses the Kotlin compiler frontend for full type resolution, ensuring that all structural changes are safe and update all references across the entire project.

Use martin instead of raw text edits when you need to make structural changes to Kotlin code. This produces fewer errors and is closer to how a human developer works in an IDE.

## Setup

Martin is distributed as a fat JAR. It requires Java 21+.

```bash
# Build from source (in the martin project directory)
./gradlew shadowJar

# The JAR is at build/libs/martin-0.1.0.jar
# Alias for convenience:
alias martin="java -jar /path/to/martin-0.1.0.jar"
```

## Performance: Daemon Mode

For faster execution, start the daemon before running multiple refactorings. The daemon keeps the Kotlin compiler environment warm, reducing each operation from ~4s to ~1.5s.

```bash
# Start daemon (in background or separate terminal)
martin daemon start -p PROJECT

# All subsequent commands automatically use the daemon if running
martin rename -p PROJECT -f FILE -l LINE -c COL --new-name newName

# Stop when done
martin daemon stop -p PROJECT
```

Commands automatically fall back to direct mode if no daemon is running.

## Commands

All commands share these common parameters:
- `--project, -p` (required): Root directory of the Kotlin/Gradle project
- `--file, -f` (required): File containing the target element
- `--line, -l` (required): Line number (1-based)
- `--col, -c` (required): Column number (1-based)

---

### Core Refactorings

#### rename

Rename any symbol (function, variable, class, parameter) and update all references across the project.

```bash
martin rename -p PROJECT -f FILE -l LINE -c COL --new-name newName
```

**When to use:** Renaming a function, variable, class, or parameter. Prefer over find-and-replace because it only renames the specific symbol and handles cross-file references and imports.

**Extra parameters:** `--new-name, -n` (required): New name for the symbol

#### extract-function

Extract a range of lines into a new function. Automatically determines parameters and return values.

```bash
martin extract-function -p PROJECT -f FILE --start-line 10 --end-line 20 --name doSomething
```

**When to use:** Breaking a long function into smaller pieces, extracting duplicated logic.

**Extra parameters:**
- `--start-line, -s` / `--end-line, -e`: Line range to extract
- `--name, -n`: Name for the extracted function

#### extract-variable

Extract an expression into a named `val` declaration.

```bash
martin extract-variable -p PROJECT -f FILE -l LINE -c COL --name result
```

**When to use:** Naming a complex expression for clarity, or reusing a computed value.

**Extra parameters:** `--name, -n`: Name for the new variable

#### inline

Inline a variable or function — replace all usages with the definition and remove the declaration.

```bash
martin inline -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** Removing unnecessary indirection — a variable used once, or a trivial wrapper function.

#### move

Move a top-level declaration to a different package. Updates all imports.

```bash
martin move -p PROJECT --symbol com.example.MyClass --to-package com.example.other
```

**When to use:** Reorganizing package structure.

**Extra parameters:** `--symbol, -s`: Fully qualified name; `--to-package, -t`: Target package

#### change-signature

Change a function's parameter list: add, remove, reorder, or rename parameters. Updates all call sites.

```bash
martin change-signature -p PROJECT -f FILE -l LINE -c COL --params "name:String,greeting:String=Hello"
```

**When to use:** Adding/removing/reordering parameters.

**Extra parameters:** `--params`: New parameter list as `name:Type=default,...`

---

### Expression & Body Conversions

#### convert-to-expression-body

Convert a function with a single return statement to expression body form.

```bash
martin convert-to-expression-body -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** Simplifying `fun f(): T { return expr }` to `fun f(): T = expr`.

#### convert-to-block-body

Convert an expression-body function to block body form.

```bash
martin convert-to-block-body -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** When you need to add multiple statements to a function that currently uses `= expr`.

#### add-named-arguments

Add parameter names to all positional arguments at a call site.

```bash
martin add-named-arguments -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** Improving readability of calls with many parameters.

---

### Extraction & Deletion

#### extract-constant

Extract a literal expression into a named constant (top-level `const val` or companion object).

```bash
martin extract-constant -p PROJECT -f FILE -l LINE -c COL --name DEFAULT_TIMEOUT
```

**When to use:** Replacing magic numbers/strings with named constants.

**Extra parameters:** `--name, -n`: Name for the constant

#### safe-delete

Delete a declaration only if it has no usages. Fails if the symbol is still referenced.

```bash
martin safe-delete -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** Removing dead code with confidence that nothing references it.

#### convert-property-to-function

Convert a property with a getter to a function.

```bash
martin convert-property-to-function -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** When a property performs computation and should be an explicit function call.

---

### Parameter Refactorings

#### extract-parameter

Extract a hardcoded expression inside a function body into a new function parameter, updating all call sites.

```bash
martin extract-parameter -p PROJECT -f FILE -l LINE -c COL --name paramName
```

**When to use:** Making a function more configurable by extracting a hardcoded value.

**Extra parameters:** `--name, -n`: Name for the new parameter

#### introduce-parameter-object

Group multiple function parameters into a data class.

```bash
martin introduce-parameter-object -p PROJECT -f FILE -l LINE -c COL \
  --class-name Config --params "timeout,retries"
```

**When to use:** When a function has too many parameters (parameter object pattern).

**Extra parameters:**
- `--class-name`: Name for the new data class
- `--params`: Comma-separated list of parameter names to group

---

### Class Hierarchy

#### extract-interface

Extract selected methods from a class into a new interface.

```bash
martin extract-interface -p PROJECT -f FILE -l LINE -c COL \
  --interface-name Readable --methods "read,close"
```

**When to use:** Creating an abstraction from a concrete class.

**Extra parameters:**
- `--interface-name`: Name for the new interface
- `--methods`: Comma-separated list of method names to extract

#### extract-superclass

Extract selected members from a class into a new abstract superclass.

```bash
martin extract-superclass -p PROJECT -f FILE -l LINE -c COL \
  --superclass-name BaseService --members "init,close"
```

**When to use:** Creating a base class from shared functionality.

**Extra parameters:**
- `--superclass-name`: Name for the new superclass
- `--members`: Comma-separated list of member names to extract

#### pull-up-method

Move a method from a subclass to its superclass.

```bash
martin pull-up-method -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** When multiple subclasses share the same method implementation.

#### replace-constructor-with-factory

Make a constructor private and create a factory method.

```bash
martin replace-constructor-with-factory -p PROJECT -f FILE -l LINE -c COL \
  --factory-name create
```

**When to use:** When you need to control object creation (caching, validation, returning subtypes).

**Extra parameters:** `--factory-name` (default: "create"): Name for the factory method

---

### Kotlin Idioms

#### convert-to-data-class

Convert a regular class with a primary constructor to a data class.

```bash
martin convert-to-data-class -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** When a class is primarily used to hold data and should have auto-generated `equals`, `hashCode`, `copy`, etc.

#### convert-to-extension-function

Convert a function's first parameter into a receiver, making it an extension function.

```bash
martin convert-to-extension-function -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** When `process(list, x)` reads better as `list.process(x)`.

#### convert-to-sealed-class

Convert an abstract class to a sealed class.

```bash
martin convert-to-sealed-class -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** When all subclasses are known and you want exhaustive `when` checking.

---

### Advanced

#### encapsulate-field

Make a public `var` property private and generate a getter/setter.

```bash
martin encapsulate-field -p PROJECT -f FILE -l LINE -c COL
```

**When to use:** Adding validation or side effects to property access.

#### type-migration

Change the type of a variable or function return type and propagate the change through data flow.

```bash
martin type-migration -p PROJECT -f FILE -l LINE -c COL --new-type "List<String>"
```

**When to use:** Changing a type and having dependent declarations update automatically.

**Extra parameters:** `--new-type, -t`: The new type to migrate to

#### move-statements-into-function

Move statements that appear before/after a function call into the function body itself.

```bash
martin move-statements-into-function -p PROJECT -f FILE \
  --function-line 5 --function-col 5 \
  --start-line 10 --end-line 12
```

**When to use:** When the same setup/teardown code appears at every call site and should be inside the function.

**Extra parameters:**
- `--function-line` / `--function-col`: Location of the function declaration
- `--start-line` / `--end-line`: Line range of statements to move in

---

### Metrics

#### report

View refactoring usage metrics for the current project.

```bash
martin report -p PROJECT
martin report -p PROJECT --format json
```

**When to use:** Understanding which refactorings are used most, success rates, and performance.

**Extra parameters:** `--format` (default: "text"): Output format, either `text` or `json`

---

## Workflow Tips for Agents

1. **Prefer refactorings over raw edits.** Instead of manually editing 5 files to rename a function, use `martin rename`. It's safer and handles edge cases.

2. **Chain refactorings.** Complex changes can be broken into a sequence:
   - Extract a block into a function
   - Rename it for clarity
   - Change its signature to accept a new parameter
   - Move it to a more appropriate package

3. **Use line:col from your editor context.** When you know a symbol's location, pass it directly. Martin resolves the symbol semantically from that position.

4. **The project must be parseable.** Martin uses the Kotlin compiler frontend, so the code needs to be syntactically valid Kotlin. It does not need to compile successfully (unresolved external dependencies are tolerated).

5. **First analysis is ~2-5s.** Martin re-parses the project on every invocation. For multiple refactorings, this is acceptable since each refactoring is a separate atomic operation.

6. **Use `report` to track patterns.** After many refactorings, `martin report` shows which operations are most common, helping identify workflow improvements.
