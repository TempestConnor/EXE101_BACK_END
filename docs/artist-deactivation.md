# Artist deactivation and reinstatement (F03)

Decisions recorded 2 November 2026 for the priority-2 increment of F03. Under the 31 October amendment (§A.4),
suspension and appeals are replaced by deactivation: timed suspension, automatic expiry and appeals are removed, not
deferred. Commission effects (pending requests and Quotes, Q05) belong to priority 3.

## Approved policies

- **Representation (schema, reuse as-is):** no migration. A deactivated Artist has `ArtistProfile.Status =
  'SUSPENDED'`, the schema's existing value, and one `ArtistSuspension` row with `EndsAt` always `NULL`. The API and
  these docs say "deactivated"; the database says `SUSPENDED`. `UX_OpenSuspension` (`ClosedAt IS NULL`) allows one
  open row per Artist. `ArtistAppeal` stays unused.
- **Reinstatement (amendment §E, decided):** deactivation can be reversed by the Admin. Reinstatement closes the open
  row (`ClosedAt`) and sets the status back to `ACTIVE`. Closed rows are the retained history; a later deactivation
  opens a new row.
- **Held funds (amendment §E, decided: unchanged):** deactivation does not touch money. Proceeds already `HELD` keep
  their milestones, and an Order created before the deactivation stays payable until it expires, so new `HELD`
  proceeds can still arrive. Releases continue as in [Artist proceeds](artist-proceeds.md): digital at payment,
  physical at the return-window close, each transferred by the Admin to the Artist's own account. The deactivated
  Artist can still read their proceeds and releases.
- **Who and why:** only an active `ADMIN`; `MODERATOR` and Customer tokens get `403`. A reason is required (at most
  2000 characters) for both actions. The deactivation reason is stored on the row; the reinstatement reason is
  stored in its audit record.
- **Errors:** an unknown Artist is `404 ARTIST_NOT_FOUND`; deactivating a deactivated Artist is
  `409 ARTIST_ALREADY_DEACTIVATED`; reinstating an active one is `409 ARTIST_NOT_DEACTIVATED`.
- **Audit (§29):** `ARTIST_DEACTIVATED` and `ARTIST_REINSTATED`, target `ArtistProfile`, with the Admin, the reason
  and `deactivationId=<row>` in the details, in the same transaction as the change.
- **No rewriting (F03):** Listings, Artwork and `AcceptingCommissions` are not changed. Purchase availability is
  already computed from the Artist status ([Listings](listing-catalog.md)), so Carts show the items as unavailable
  and checkout is blocked, and reinstatement makes them purchasable again with no Listing write.
- **Customer access (§2.14–15):** a deactivated Artist keeps every Customer capability, including buying from other
  Artists. Publishing, and the other Artist edits that already require `ACTIVE`, are refused with
  `409 ARTIST_NOT_ACTIVE`.
- **History:** no read or browse endpoint; the action's response returns the record. Audit browsing tools are
  deferred ([scope](implementation-scope.md#admin-auditing-scope-f21)).

## Concurrency

- Deactivation and reinstatement lock the `ArtistProfile` row (`UPDLOCK`) before checking its status, the same lock
  Order creation takes on each Artist it sells from. An Order in progress commits first and stays payable under its
  own terms; an Order that starts later sees the deactivation and is blocked. A second deactivation waits, then gets
  `409`. `UX_OpenSuspension` is the database guard.
- Publishing an Artwork or a Listing takes a shared lock on the Artist row before checking `ACTIVE`. It is
  compatible with checkout's lock, so publishing and checkout never deadlock. A status change waits for a publication
  in progress, and a publication waits for a status change in progress, so nothing is published after a
  deactivation commits.

## HTTP contract

| Endpoint | Request | Success |
| --- | --- | --- |
| `POST /admin/artists/{artistId}/deactivation` | Admin bearer token; `reason` | 200 with `deactivationId`, `artistId`, `artistStatus: "SUSPENDED"`, `startedAt`, `closedAt: null`, `reason`, `staffId` |
| `POST /admin/artists/{artistId}/reinstatement` | Admin bearer token; `reason` | 200 with the same fields, `artistStatus: "ACTIVE"` and the `closedAt` just set |

## Weaknesses

- **Held money still reaches a removed member automatically.** A member removed for misconduct keeps receiving
  releases at each milestone, including from Orders paid after the deactivation. Refund windows close before
  release, so Customers stay covered, but the team has no way to hold the money back. Closing it would take a policy
  for a leaving member's held funds and a release hold that the Admin sets and lifts, posted to the ledger and
  covered by reconciliation.
- **No history view.** Past deactivations are kept in `ArtistSuspension` and `AdminAudit` but can only be read in
  the database until audit tools are built.

## Tests

`ArtistServiceTest` and `ArtistAdminControllerTest` are unit tests. `ArtistDeactivationAuditRollbackTest` needs
`DB_URL` and removes its rows. `ArtistDeactivationPersistenceTest` commits Orders and payments, so it runs only
against the disposable test database selected by `TEST_DB_URL`, like the F14 suites, and leaves its rows there.
