# Layered architecture

Each feature is one package, split into layers. The table below is the contract for what each layer is allowed to touch — and, just as importantly, what it isn’t:

| Layer | Responsibility | May import | Must NOT |
| --- | --- | --- | --- |
| `controller` | bind + validate the request, call one service method, map the result to a DTO, set the status | `service`, `dto`, `mapper`, `model` | touch `repository` / `entity`; contain branching business logic; check existence |
| `service` | the feature’s logic: transactions, not-found, uniqueness checks, `Pageable`/`Sort`, orchestration | `repository`, `model`, `mapper`, other features’ `service` + `dto` | import a controller; import another feature’s `repository` / `entity` |
| `repository` | persistence — Spring Data interfaces only | `entity` | be a hand-written class; hold logic beyond derived queries / `@Query` |
| `model` | the in-memory representation the service works with | Lombok, other `model` types | import `jakarta.persistence.*`, Spring, or a DTO |
| `entity` | the database row | `jakarta.persistence.*`, Lombok | hold behaviour; be returned from a controller |
| `dto` | the wire shape (request / response) | — | diverge from your OpenAPI contract |
| `mapper` | copy fields between two representations | the two types it maps | call a service or repository; do I/O |

Lay it out as a request flow and it reads almost like a sentence — a DTO becomes a model, a model becomes an entity, and the response walks the same path back:

```text
POST /<feature>
  Controller(Create<Feature>DTO)
    → <Feature>Mapper.toModel(dto)                  dto  → model
    → <Feature>Service.create(model)                @Transactional
        → <Feature>EntityMapper.toEntity(model)     model → entity
        → repository.save(entity)
        → <Feature>EntityMapper.toModel(saved)      entity → model
    → <Feature>Mapper.toDto(model)                  model → response dto
```

A paged read follows the same idea: the controller hands a `Pageable` straight to the service, the service returns a `Page<Model>`, and the controller wraps that into one list envelope (e.g. `{ metaData, data }`) — see [docs/controllers.md](controllers.md) for how that wrapping actually looks in code.

A couple of rules hold the whole thing together. The service is an interface plus one implementation, and everyone — controllers, other features — depends on the interface, never the impl directly. The repository stays a Spring Data interface; “find or 404” is the service’s job, not the repository’s.

`model/` never imports a framework type, and `entity/` never carries behaviour. And a feature is free to call another feature’s `service/` and use its `dto/`/`model/`, but it may never reach into another feature’s `repository/` or `entity/` directly — that’s the one hard boundary between features.
