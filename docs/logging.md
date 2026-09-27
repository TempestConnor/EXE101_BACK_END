# Logging

`@Slf4j` from Lombok, always — no hand-rolled Logger instances. Reach for WARN / ERROR when something has gone wrong, and INFO for actions worth a trail (a resource created, a job finished).

The message template stays consistent across the codebase: `log.info("[<FEATURE>] - ACTION: <method>: <field>: {}", value)`, with `{}` placeholders — never string concatenation, which turns into either a performance cost or an accidental leak of something sensitive.

Include identifiers that let you trace a request end to end (request ID, user ID), and never log anything sensitive. And keep logging in the service / application layer — a thrown domain exception shouldn’t also log, for the same reason covered in [docs/exceptions.md](exceptions.md): one failure, one log line.
