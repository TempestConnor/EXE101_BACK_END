# Controllers

Think of `<Feature>Controller` as the HTTP edge and nothing more: it maps the request DTO to a model, calls the service, maps the result back to a response DTO. No business logic, no existence checks, no `@ExceptionHandler` living locally — see [docs/exceptions.md](exceptions.md) for where that goes instead.

The one habit worth drilling into muscle memory: every mapping is its own statement, assigned to a `var`, and the service call is its own statement too. Never nest a mapper call inside the service call, and never nest the service call inside a mapper call.

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public TaskDTO createTask(@RequestBody @Valid CreateTaskDTO dto) {
    var task = TaskMapper.toModel(dto);
    var created = taskService.create(task);
    return TaskMapper.toDto(created);
}
```

Not this — it saves two lines and costs you a debugger breakpoint the day something in here throws:

```java
// ✗ nested mapping — request-map buried in the argument list, response-map wrapping the call
return TaskMapper.toDto(taskService.create(TaskMapper.toModel(dto)));
```

A couple of smaller conventions round it out. One or two filter parameters are fine as individual `@RequestParam`s; three or more (dates, enums, and so on) deserve one object bound with `@ModelAttribute` instead of a long parameter list. Paged search endpoints take a `Pageable` and return the list envelope; name the handler and its service method `search…`, never `list…`, even when there’s no filter to speak of. And on status codes: `CREATED` on create, `NO_CONTENT` on delete, everything else defaults to 200.

Before you commit a controller method, check it against this:

- `var` locals only — no `final`, no explicit types.
- Request-map → service call → response-map are separate lines, each into a `var`.
- No mapper call nested in a service call or another mapper call.
- No `findById(...).isPresent()` / existence checks — throw from the service instead.
