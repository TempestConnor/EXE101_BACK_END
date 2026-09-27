# Backend — Spring Boot

Standards: [docs/java-style.md](docs/java-style.md) · [docs/annotations.md](docs/annotations.md) ·
[docs/layered-architecture.md](docs/layered-architecture.md) · [docs/controllers.md](docs/controllers.md) ·
[docs/mappers.md](docs/mappers.md) · [docs/exceptions.md](docs/exceptions.md) ·
[docs/testing.md](docs/testing.md) · [docs/logging.md](docs/logging.md)

Read [repository guidance](docs/repository-guidance.md) for requirements, conflicts, database constraints, build commands, and validation reporting. Its repository-specific exceptions take precedence over the supplied topic templates.

## Stack

Target conventions: Java 25 · Spring Boot 4.x · Maven or Gradle · Spring Data JPA · Lombok · JUnit 6 + Mockito + AssertJ.

Currently configured in [pom.xml](pom.xml): Java 21 target, Spring Boot 4.1.1, Maven Wrapper, Spring MVC, Spring Data JPA, SQL Server, and Boot test starters. Lombok and MapStruct are configured with explicit annotation processors. Compile against the configured Java version until the build is upgraded.

## Feature package layout

One package per feature, split into layer subpackages:

```text
com/exe101/<feature>/
  controller/   <Feature>Controller     @RestController @RequestMapping("/<feature>")
  service/      <Feature>Service        interface — the feature's public surface
                <Feature>ServiceImpl    @Service, class-level @Transactional — the one impl
  repository/   <Feature>Repository     interface extends JpaRepository<<Feature>Entity, Id>
  model/        <Feature>, enums        plain POJO (Lombok), no jakarta.persistence imports
  entity/       <Feature>Entity         @Entity only, no logic
  dto/          Create/Update/Response records
  mapper/       <Feature>Mapper         MapStruct interface — DTO ↔ model
                <Feature>EntityMapper   MapStruct interface — model ↔ entity
```

Not every feature needs every file — a read-only feature has no `Create<Feature>DTO`. Add a layer only when it actually carries weight.

Apply this layout to new features. Existing generated entities remain in `com.exe101.entity` unless a task explicitly includes their migration.

## Gotchas

- The service is an interface `<Feature>Service` plus one `@Service` implementation `<Feature>ServiceImpl` (both in `service/`). Inject and mock the interface; `@InjectMocks` in the service's own unit test targets `<Feature>ServiceImpl` — Mockito can't instantiate an interface.
- The repository is a plain Spring Data interface, nothing hand-written. "Find or 404" is a service concern: `repository.findById(id).orElseThrow(() -> new AppException(AppErrorMessage.<X>_NOT_FOUND))`. These exception types are conventions to implement, not existing classes.
- `model/` holds plain POJOs (no JPA imports); `entity/` holds `@Entity` classes only, no logic.
- **No `final` on method parameters or local variables, anywhere.** Production code, controllers and tests all agree on this; `final` survives only on Lombok constructor-injected fields, because `@RequiredArgsConstructor` needs it.
- `var` in controllers and tests; explicit types in services and mappers.
- No existence checks or business logic in controllers — that belongs in the service, which should throw when something isn't there.
- `@Transactional` at class level, service classes only (`readOnly = true` for read paths). The existing read-only mapping test remains an intentional test exception.

Structure follows the [Java/Spring Boot AGENTS.md guide](https://josealopez.dev/en/blog/agents-md-java-spring-boot); topic files apply it to this repository.
