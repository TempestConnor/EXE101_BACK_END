# Exceptions

Rather than a new exception class for every failure mode, keep one domain exception type that carries an error-code enum: `throw new AppException(AppErrorMessage.COMMUNITY_NOT_FOUND)`.

New failure modes just mean a new enum value, not a new class to wire up.

Everything funnels through one `GlobalExceptionHandler` (`@ControllerAdvice` + `@ExceptionHandler`), which is the single place that maps exceptions to HTTP status codes and to a consistent error-response shape. Resist adding a local `@ExceptionHandler` in a controller — it just means there are now two places to check when a status code looks wrong.

One rule that’s easy to miss: code that throws a domain exception doesn’t also log it. The global handler logs once, in one place; logging at the throw site too just writes the same failure to your logs twice.
