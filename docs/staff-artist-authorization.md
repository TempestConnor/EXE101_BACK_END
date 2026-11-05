# Staff authentication and Artist authorization (F02)

This implements the standard-order release's staff login and Admin-only Artist
authorization. Artist deactivation is F03 (priority 2). The self-purchase guard
("an Artist cannot buy their own Listing") is an F02 rule but is enforced in Cart
and Order creation (F09/F10), which do not exist yet.

## Agreed behavior

Decisions confirmed on 31 October 2026:

- The single Admin account is created at startup from configuration (below).
  There is no password-recovery workflow; a reset is a direct database change.
- Staff login mirrors F01: 12–128 character passwords, PBKDF2 hashes, no trimming,
  login names normalized by whitespace stripping and locale-independent lowercase.
  Incorrect credentials and inactive accounts share the same 401 response.
- Staff tokens are checked against the current `StaffAccount` row on every request:
  a deactivated account's existing tokens stop working, and the role is read from
  the row, never from the token.
- Only an active `ADMIN` may grant an Artist Profile. `MODERATOR` accounts are
  authenticated but cannot grant.
- The target User must exist and be `ACTIVE` (not `BLOCKED` or `DELETED`) and must
  not already have an Artist Profile.
- A new profile starts `ACTIVE` and closed to Commissions.
- The Admin stays separate from the Artist team: staff accounts never act as Artists.
- Each grant writes an `AdminAudit` record (`ARTIST_PROFILE_GRANTED`, target
  `ArtistProfile`) in the same transaction, with an optional reason.

## HTTP contract

| Endpoint | Request | Success |
| --- | --- | --- |
| `POST /staff/login` | `loginName`, `password` | 200 with `accessToken`, `tokenType: "Bearer"`, `expiresIn: 7200` |
| `POST /artists` | Admin bearer token; `userId`, `displayName`, optional `reason` (≤ 2000) | 201 with `artistId`, `userId`, `displayName`, `status`, `acceptingCommissions`, `createdAt` |
| `GET /artists/me` | Customer bearer token | 200 with the caller's own Artist Profile (`artistId`, `userId`, `displayName`, `status`, `acceptingCommissions`, `startingPrice`, `createdAt`), deactivated (`SUSPENDED`) or not; `403 ARTIST_PROFILE_REQUIRED` when the Customer has none |

`GET /artists/me` (added 5 November 2026, from the frontend integration report) lets the storefront tell whether the
signed-in Customer is an Artist and find their own `artistId`, for example for the terms page before any Artwork
exists.

Errors follow the F01 Problem Details contract. New codes: `USER_NOT_FOUND` (404),
`USER_NOT_ACTIVE` (409) and `ARTIST_ALREADY_GRANTED` (409). A Customer token on a
staff route, a staff token on a Customer route, or a non-Admin staff token on the
grant route returns 403 (`ACCESS_DENIED`).

## Tokens

Staff tokens use the same HS256 key (`JWT_SECRET`) and lifetime as Customer tokens,
with their own issuer (`exe101-staff`) and audience (`exe101-staff-api`). One
security filter chain reads a token's issuer only to choose its verifier; that
verifier then checks the signature, issuer, audience, timestamps, token type and
subject. A Customer token therefore never authenticates as staff, even when its
User ID equals a Staff ID. The shared pieces live in `com.exe101.common.security`.

## Admin bootstrap

Set both variables before starting the application:

```powershell
$env:ADMIN_LOGIN_NAME = 'admin'
$env:ADMIN_PASSWORD = '<12-128 characters>'
```

On startup, if no `ADMIN` row exists (active or not), one active Admin is created.
If an Admin already exists, nothing changes and its password is never overwritten.
If neither variable is set, nothing happens. A login name or password outside the
allowed lengths fails startup. The database's `UX_OneAdmin` index still guarantees
at most one active Admin.

## Persistence and validation

No migration is required. The duplicate-grant race is decided by the existing
unique constraint on `ArtistProfile.UserId`; its name is system-generated, so the
service recognizes SQL Server duplicate-key errors (2601/2627) rather than a name.
`AuditService` requires an existing transaction, so an audit record can only commit
with the action it describes.

Run the suites as described in [Customer authentication](customer-authentication.md#persistence-and-validation).
`ArtistPersistenceTest` and `StaffPersistenceTest` roll back their writes, except
the concurrent-grant test. That test and `ArtistAuditRollbackTest` briefly commit
an `f02-test-<UUID>@example.invalid` User (and an `f02-test-<UUID>` Admin when the
database has none) and remove them afterward. `admin_bootstrap_creates_admin_ok`
is skipped when the database already has an Admin.
