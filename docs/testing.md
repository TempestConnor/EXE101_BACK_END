# Testing

JUnit 6, Mockito, AssertJ. Tests mirror the feature’s own layer subpackages, and not every layer needs one: a service test is always there, because that’s where the business logic actually lives.

A controller test (`@WebMvcTest`) earns its place once the endpoint has validation, status mapping, or a response shape worth asserting on; a repository test (`@DataJpaTest`) is for a custom `@Query` or `Specification` — never for plain CRUD, which Spring Data already tested for you.

One easy-to-get-backwards detail: in the service’s own unit test, `@InjectMocks` targets the impl — Mockito can’t instantiate an interface. Everywhere else, `@Mock` / `@MockitoBean` the interface instead.

The rest is mostly naming and shape. Test names are snake_case, ending in `_ok` or `_ko` (`create_task_ok`, `get_task_not_found_ko`) — though a longer, more descriptive name is fine when it genuinely reads better. Every body follows `// given` / `// when` / `// then`.

Assertions go through AssertJ (`assertThat`, `assertThatThrownBy`), and related checks get grouped into one `assertAll(...)` so a failure shows every mismatch at once instead of just the first. And tests flip the style rule from earlier: `var` for locals, no `final` — the same as controllers, and the opposite of the rest of production code.

Field injection (`@Mock` / `@InjectMocks` / `@Autowired`) is fine here — it’s only banned in production code. Keep reflection and business logic (loops, conditionals) out of a test body; if you need a helper, write a small private factory method instead.

One behaviour per test, and per service, make sure you’ve covered: the happy path, the not-found case, any guard clause that should reject before a save happens (`verify(repo, never()).save(any())`), and PATCH semantics — that a partial update really only touches the fields it was given.

Treat this as a quality gate, not an afterthought: nothing merges without the formatting check and the full test suite passing — `./mvnw spotless:check && ./mvnw test`. Wire it into CI, and into a pre-commit or pre-push hook locally if your team likes catching it even earlier.
