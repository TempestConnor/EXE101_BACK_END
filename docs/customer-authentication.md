# Customer authentication (F01)

This implements the standard-order release's Customer registration and login
dependency. Account deletion remains deferred. Staff login and Artist grants
belong to F02 and are not implemented here.

## Agreed behavior

Decisions confirmed on 27 September 2026:

- Email/password login issues a bearer access token.
- Passwords contain 12–128 characters, with no composition requirements.
  Passwords are not trimmed or silently truncated.
- Email verification is not required for the controlled demo.
- BLOCKED and DELETED accounts cannot log in or use authenticated APIs.
  Current account status is checked on every authenticated request, including
  requests using a previously issued token.

Registration and login normalize emails consistently using whitespace stripping
and locale-independent lowercase. HTTP requests must contain a valid email of at
most 320 characters. Registration requires a nonblank display name of at most
160 characters. The API never accepts identity, role, lifecycle, or hash fields
as registration inputs.

The existing `UX_User_Email` index is the final authority for uniqueness,
including concurrent registrations and blocked accounts. A deleted account has
its email released by the existing schema contract; registration always inserts
a new identity and never reactivates or overwrites a historical account.
No account-deletion endpoint or workflow is added.

## HTTP contract

| Endpoint | Request | Success |
| --- | --- | --- |
| `POST /customers/register` | `email`, `password`, `displayName` | 201 with `userId`, `email`, `displayName`, `createdAt` |
| `POST /customers/login` | `email`, `password` | 200 with `accessToken`, `tokenType: "Bearer"`, `expiresIn: 900` |
| `GET /customers/me` | `Authorization: Bearer <accessToken>` | 200 with the authenticated Customer's public fields |

Registration does not log the Customer in automatically. Log in after registration.
All other routes are denied until their feature explicitly defines authorization.
Customer tokens cannot authorize staff operations. No cookies or HTTP sessions
are created. Clients send tokens in the Authorization header, never URLs.

Errors use `application/problem+json`, with standard Problem Details fields and
an `errorCode` extension. Validation/malformed requests return 400
(`INVALID_REQUEST`); duplicate email returns 409 (`EMAIL_ALREADY_REGISTERED`).
Incorrect credentials and unavailable accounts share a 401 response
(`INVALID_CREDENTIALS`). Missing, expired, malformed or otherwise invalid bearer
tokens return 401 (`AUTHENTICATION_REQUIRED`) with a Bearer challenge. Forbidden
routes return 403 (`ACCESS_DENIED`). Unexpected application failures return a
generic 500 (`INTERNAL_ERROR`); database failures are not credential failures.

## Tokens and password storage

Access tokens are HS256 JWTs valid for 15 minutes, with Customer-specific issuer
and audience, a stable User ID subject, issue/expiry timestamps and `type=access`.
Signature, algorithm, issuer, audience, timestamps, token type and subject are
validated before current account state is checked. Tokens contain no email,
password or client-selected roles. Invalid supplied tokens are rejected even on
the public registration/login routes.

The demo has no refresh-token or per-token revocation endpoint. Clients log in
again after expiry and discard their token when logging out. Blocking an account
denies its existing tokens while it is blocked; it does not permanently revoke
tokens if that account is later reactivated before token expiry. Changing the
signing secret invalidates all existing tokens.

Passwords use Spring Security's salted PBKDF2 encoder with a stored algorithm
identifier, encoded into the existing `varbinary(512)` column. This supports the
full password limit, including multibyte text, without bcrypt's byte truncation.
Hashes and credentials are never exposed in responses or application logs.

## Local setup

Set `JWT_SECRET` to Base64-encoded cryptographically random bytes (at least 32),
then run the existing helper. To create an ephemeral development key in the
current PowerShell session:

```powershell
$jwtKeyBytes = New-Object byte[] 32
$jwtRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$jwtRandom.GetBytes($jwtKeyBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($jwtKeyBytes)
$jwtRandom.Dispose()
.\scripts\dev.ps1 Run
```

Keep a stable secret in private environment configuration if tokens must survive
restarts. There is no application default key. Tests use a test-only key from
`src/test/resources/application.yaml`, which is not packaged in the application.
The application fails startup for a missing or insufficiently long key.

## Persistence and validation

No migration is required. The implementation preserves all 45 entity mappings,
database-generated identity/rowversion, UTC timestamps, schema validation, and
disabled SQL initialization/open-in-view.

Run the non-database suite with `.\mvnw.cmd clean package`. Database tests are
skipped when `DB_URL` is absent. `.\scripts\dev.ps1 Verify` still runs only the
read-only mapping tests. To load local settings and run the full database suite
in the same PowerShell process:

```powershell
. .\scripts\dev.ps1 Verify
.\mvnw.cmd clean package
```

`CustomerPersistenceTest` verifies persisted registration/login, rowversion,
blocked-account access, preservation of a previously deleted identity, and a
concurrent duplicate-registration race. Most writes roll back. The concurrency
test briefly commits a newly created account under a unique
`f01-test-<UUID>@example.invalid` email and removes that test account afterward.
Do not point these write tests at a production database.

The next feature is F02: separate staff authentication and audited Admin Artist
authorization. Its staff provisioning/recovery questions still require a product
decision. Deletion and historical entitlement recovery under Q16 remain unresolved
and deferred; this F01 increment does not settle them.

Implementation references: [Spring Security JWT support](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
and [password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
