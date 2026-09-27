# Annotations

**Lombok** does most of the boilerplate work: `@RequiredArgsConstructor` for constructor injection, `@Slf4j` for logging, `@Builder(setterPrefix = "with")` when an object is complex enough to need one. `@Data` is off the table — it’s too much at once — so reach for `@Getter` / `@Setter` individually instead.

`model/` POJOs carry the full set (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder(setterPrefix = "with")`) and never import `jakarta.persistence`; `entity/` classes carry the same Lombok set plus the JPA annotations, and nothing else — no behaviour.

**Spring’s** annotations mostly do what you’d expect, with a few placement rules worth being explicit about:

- **`@RestController`** on web controllers, with the HTTP-method annotations (`@GetMapping` / `@PostMapping` / `@PatchMapping` / `@DeleteMapping`) at method level.
- **`@Service`** on the feature’s one implementation, `<Feature>ServiceImpl`, which `implements <Feature>Service` — a plain interface, carrying no annotations of its own.
- **`@Repository`** is never written by hand: repositories are Spring Data interfaces (`extends JpaRepository<…>`) and Spring registers them for you. No `*RepositoryImpl`, no adapters.
- **`@Component`** for generic Spring beans, **`@Configuration`** for configuration classes.
- **`@Autowired`** means constructor injection in production code; field injection is reserved for tests.
- **`@ConfigurationProperties`** once you’re binding three or more related properties — below that, individual `@Value`s are fine.
- **`@Transactional`** lives at class level, on `@Service` classes only, so transaction management doesn’t leak into individual methods; use `@Transactional(readOnly = true)` for read paths.
- **`@Validated`** to turn on Bean Validation on method parameters or classes; `@RequestBody @Valid` on write DTOs.
- **`@PreAuthorize`** at the controller layer when Spring Security is enforcing method-level access.

Keep dependencies acyclic, and don’t reach for `@Order` to paper over a resolution problem — fix the dependency instead.
