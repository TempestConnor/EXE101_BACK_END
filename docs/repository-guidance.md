# Repository guidance

## Requirements and conflicts

- Read [README.md](../README.md) for setup and persistence conventions, [business rules](Marketplace_Business_Rules_Final.md) for business behavior and scope, and [feature specifications](EXE101_Features_Specification.md) for F01–F21, acceptance criteria, cross-feature rules, and open questions.
- The feature specification derives from the business rules; SQL is an implementation baseline, not a source of additional business policy. Report conflicting passages or implementation mismatches explicitly; do not silently resolve them by inventing policy or changing the schema.
- Consult section D of the feature specification (Q01–Q19) and feature-level **Needs Clarification** notes before implementing affected behavior. Ask for the missing decision, explain the affected path, and continue independent work. Do not turn an unresolved question into an assumed default.
- `EXE101_SQL_Schema.sql` and `Marketplace_Schema_Notes.md` are referenced by the specification but are not currently in this repository. Do not claim to have checked them; obtain the relevant source when needed.

## Existing implementation and database

- [pom.xml](../pom.xml) defines a single-module Spring Boot 4.1.1 application targeting Java 21, with Spring MVC, Spring Data JPA, and SQL Server. Code is under `src/main/java/com/exe101`: currently the application entry point, 45 entities, and five composite ID classes. No API endpoints or controller/service/repository layers are implemented yet; do not describe a proposed architecture as established.
- The existing SQL Server database is the schema authority. Preserve `ddl-auto=validate`, disabled SQL initialization, and disabled open-in-view in [application.properties](../src/main/resources/application.properties). Do not enable automatic schema creation/update to fix mapping failures.
- Follow [README persistence guidance](../README.md#generated-entities): foreign keys currently use scalar IDs; avoid duplicate writable mappings of shared columns. Preserve database-generated `rowversion`, Unicode mappings, decimal precision, UTC `OffsetDateTime` values, and explicit required insert values. SQL defaults are not Java initializers, and JPA annotations do not fully represent filtered indexes or database constraints.
- Follow the linked requirements for historical retention, immutable snapshots, and transaction/concurrency integrity; entity mappings alone do not enforce all business invariants.
- Keep credentials and `.local` private. See [local setup](../README.md#local-development-powershell) for environment overrides; only the helper loads `.local/database.json`.

## Topic template application and exceptions

The eight topic files preserve the user-supplied text from [Jose López's guide](https://josealopez.dev/en/blog/agents-md-java-spring-boot), with Markdown cleanup and local cross-links. Apply these repository-specific exceptions:

- MapStruct is the selected mapping approach. The static mapper section and static calls in controller/flow examples are retained as source examples, not an instruction to mix approaches. Use injected mapper instances (`taskMapper.toModel(...)`) for both DTO/model and model/entity boundaries. MapStruct annotations and generated Spring integration are allowed by the mapper layer's import rule.
- For Lombok `@Builder(setterPrefix = "with")` targets, explicit builder mappings may be needed: `@Mapping(source = "displayName", target = "withName")`. Outbound mappings use getter properties (`name`). `Mappers.getMapper(...)` is suitable for standalone mappers without injected collaborators; use Spring or explicitly wired collaborators when a mapper has dependencies.
- Existing generated entities and composite IDs retain their generated code, mappings, package, and generation workflow. Do not retrofit Lombok or the template's style rules into them incidentally.
- Preserve `BigDecimal` money/rates and UTC `OffsetDateTime` mappings. Incoming mappings must deliberately exclude server-controlled identifiers, versions, financial snapshots, and status fields as required by the operation.
- The controller checklist asks for a response local even though its example returns a mapper call directly. Use `var response = taskMapper.toDto(created);` followed by `return response;` to follow the checklist.
- Spotless, Palantir formatting, and their CI/hooks are not configured. The topic quality gate describes the desired setup, not a check currently available or satisfied. Use the actual commands below and report formatting validation as unavailable until tooling is configured. No formatter or CI setup is performed by this documentation update.
- There is no established OpenAPI contract, list envelope, or implemented `AppException`/`AppErrorMessage`/`GlobalExceptionHandler` yet. Examples define conventions, not existing functionality. The default success codes do not replace appropriate error responses.
- Keep the read-only [DatabaseMappingTest](../src/test/java/com/exe101/DatabaseMappingTest.java), including its transaction annotation and entity-query loop. The [processor integration test](../src/test/java/com/exe101/tooling/AnnotationProcessingTest.java) verifies Lombok/MapStruct and Spring mapper registration without a database; `Verify` does not select it.
- Application logging does not replace mandatory persistent Admin audit/lifecycle records. Never log local connection settings, tokens, protected files, or complete personal-data-bearing requests.

The supplied “What Could Be Added Later” workflow remains a proposal, not an active agent policy. No `agentic-workflows.md`, mandatory commit workflow, delegation requirement, or additional approval gate is adopted by copying the topic files.

## Build and validation (PowerShell)

Use JDK 21 or newer with `JAVA_HOME` set and the Maven Wrapper.

| Command | Purpose and limits |
| --- | --- |
| `.\mvnw.cmd clean package` | Build/package. With `DB_URL` unset, the current database tests are skipped; this is not database validation. |
| `.\scripts\dev.ps1 Verify` | Loads database settings and runs Maven `verify -Dtest=DatabaseMappingTest`: validates mappings, checks entity count, and performs read-only entity queries. Selects this test class only, not a future full suite. Requires reachable SQL Server and credentials. |
| `.\scripts\dev.ps1 Run` | Starts the application using database settings. |
| `.\scripts\dev.ps1 Generate` | Cleans `target` and stages regenerated mappings; follow the [review/apply workflow](../README.md#regenerate-after-a-schema-change), then run `Verify`. Normal builds do not regenerate source. |

The [database test](../src/test/java/com/exe101/DatabaseMappingTest.java) is enabled only for a nonempty `DB_URL`. Run checks appropriate to the change. Report commands actually run, outcomes, skipped tests, and any unavailable checks with reasons. Never present a skipped test, an unrun check, or a successful package build as proof of database or feature correctness.
