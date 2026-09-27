# Java style

Most of this is boring on purpose — formatting shouldn’t be a matter of taste, so pick a rule and stop arguing about it. 4-space indentation, 120-character lines, IntelliJ IDEA’s default Java style, UTF-8.

Blank lines separate logical blocks, and that includes putting a blank line before and after a `return` statement, a `for` loop, or a stream chain — unless it’s the first or last line in its block.

Don’t rely on everyone remembering the rules by hand, either — enforce the formatting with a plugin. [Palantir Java Format](https://github.com/palantir/palantir-java-format) via [Spotless](https://github.com/diffplug/spotless) is a solid default: `./mvnw spotless:apply` fixes a file, `./mvnw spotless:check` fails the build if someone forgot to run it.

Wire that check into CI and into whatever quality-gate step runs before a commit, alongside the test suite — formatting review shouldn’t cost a human reviewer’s attention.

Two rules that surprise people coming from a more defensive style:

- **No `final` on method parameters or local variables, anywhere.** It stays only on Lombok constructor-injected fields (`private final XRepository x;`), because `@RequiredArgsConstructor` needs `final` (or `@NonNull`) to include a field in the generated constructor. Everywhere else it’s just noise.
- **`var` in controllers and tests; explicit types in services, mappers and the rest of production code.** See [docs/controllers.md](controllers.md) and [docs/testing.md](testing.md) for why those two get the shorthand.

The rest reads like a normal style guide: no more than 3 parameters on a method or constructor — if you need more, that’s usually a sign the related ones belong in a record. Prefer immutability, especially inside for-each loops or `Stream.forEach()`. No magic numbers or strings — name them.

Check nullness and emptiness before you touch a collection or a string. Skip `throws` clauses in favor of unchecked exceptions, skip comments except for cron expressions, regex patterns, `TODO`s, or given/when/then separators in tests, and use `@Override` whenever you’re overriding something.

For one or two variables, a plain `== null` reads better than `Objects.isNull()`. Wrap compound conditions in a named boolean instead of inlining them, prefer early returns over nested `if`/`else`, and skip both wildcard imports and javadocs.
