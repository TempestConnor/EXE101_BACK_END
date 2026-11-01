# Artist proceeds and releases (F14)

Decisions recorded 1 November 2026 for the F14 increment. Order Items only; Quote proceeds belong to priority 3.

Under the 31 October amendment a payout is a **release** of held money to the Artist's own account (§B.6). The
`Payout`/`PayoutLine` tables record releases, and their states are the payout states of §15.3.

## Approved policies

- **Releases (B1, Q13, Q19, option a):** one automatic release per item. When an item reaches its release milestone,
  its proceeds become `ELIGIBLE` and, in the same transaction, a `PENDING` release with one line is created and the
  proceeds become `RESERVED`. The Admin transfers the money outside the system, to an account the Artist holds in
  their own name, and records the outcome. There is no payout provider. The Artist's bank details are not stored,
  because the schema has no field for them. Adding one would need its own schema decision.
- **Release outcomes (B1):** `PENDING → PROCESSING`, `PENDING|PROCESSING → PAID` and `PENDING|PROCESSING → FAILED`.
  `PAID` and `FAILED` are final.
  - `PAID` needs the transfer reference. It is stored as `ProviderName = MANUAL` with `ProviderPayoutRef`, and a
    reference is accepted only with `PAID`. `UX_Payout_ProviderRef` makes one bank transfer settle one release.
    Then the release and its proceeds become `PAID`, with the same `PaidAt`.
  - `FAILED` deactivates the line and returns the proceeds to `ELIGIBLE`. The Admin then starts a new release for
    them with `POST /admin/proceeds/{proceedsId}/releases`. No transfer is retried automatically, and a timeout is
    never treated as a failure: the Admin records the outcome only after checking the bank.
- **Zero proceeds (B2, option a):** a physical item whose markup is 0 still gets one proceeds record of 0 VND. At its
  milestone it becomes `PAID`, with `EligibleAt = PaidAt` set to the milestone time. No release is created, because
  release and line amounts must be above 0.
- **Holding (R1, Add.3):** the payment-success transaction creates one `HELD` proceeds record per Order Item. This
  happens after the digital grant and the fulfillment start, and only when the settlement is `PAID` (payment.md R5).
  - The proceeds belong to the item's snapshotted Artist (`OrderItem.ArtistId`), and the amount is the snapshotted
    `OrderItem.ArtistProceeds`. It is never recalculated (§7.13): the price for a digital item, quantity × markup for
    a physical one, with a fee of 0.
  - An exception settlement creates none.
  - An item that already has proceeds is skipped under the Order lock (§31.11), and `UX_Proceeds_OrderItem` is the
    database guard.
  - If the proceeds cannot be written, the whole payment success rolls back.
- **Digital milestone (R2, amendment §B.7):** the payment. A digital item's proceeds are released in the payment
  transaction, with `EligibleAt` set to the Order's `PaidAt`.
- **Physical milestone (R3, amendment §B.7–8):** the close of the 48-hour return window with no open or approved
  return. The release runs in the same transaction as the included-file grant (F13 R5), under the same Order and
  item locks and after the same checks. While a return request is open, the proceeds stay `HELD`.
- **Admin actions (R4):** only an active `ADMIN` may start a release or record its outcome. Each action writes an
  `AdminAudit` row in the same transaction, with action `RELEASE_CREATED` or `RELEASE_STATUS_CHANGED`, target
  `PAYOUT`, the reason, and details naming the statuses, proceeds and reference.
  - Locking: an outcome locks the release first and then its proceeds. A second outcome for the same release waits,
    then finds it settled and is rejected with `409`.
  - Starting a release locks the proceeds first, so only one active line can exist.
  - `UX_PayoutLine_Active` is the database guard.
- **Artist view (R5):** an Artist reads their own proceeds and releases. Staff tokens get `403`, and a Customer
  without an Artist Profile gets `403 ARTIST_PROFILE_REQUIRED`. A deactivated Artist can still read them.

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `GET /proceeds` | Customer (Artist) | `[{proceedsId, orderItemId, amount, currencyCode, status, eligibleAt, paidAt}]`, newest first |
| `GET /proceeds/releases` | Customer (Artist) | The caller's releases, newest first (shape below) |
| `GET /admin/releases?status=` | Admin | `[{payoutId, artistId, proceedsId, amount, currencyCode, status, transferReference, createdAt, paidAt}]`, newest first; all without `status` |
| `POST /admin/releases/{payoutId}/status` | Admin | Body `{status, transferReference?, reason?}` → the updated release |
| `POST /admin/proceeds/{proceedsId}/releases` | Admin | `201`, the new `PENDING` release for `ELIGIBLE` proceeds |

Errors:

- `404 RELEASE_NOT_FOUND`, `404 PROCEEDS_NOT_FOUND`
- `409 RELEASE_STATUS_CHANGE_NOT_ALLOWED`, `409 PROCEEDS_NOT_RELEASABLE`, `409 RELEASE_REFERENCE_IN_USE`
- `400 RELEASE_REFERENCE_INVALID`, `400 INVALID_REQUEST` (unknown status)
- `403` for the wrong token type

## Tests

`ProceedsPersistenceTest` commits paid Orders, so like `PaymentPersistenceTest` it runs only against
`CuratedArtworkMarketplace_Test`, selected by `TEST_DB_URL` (see [Payment](payment.md#tests)).

## Known limits

- Refunds belong to F15: setting proceeds to `VOID`, and returns that withhold a physical release. Until F15 exists
  no return requests are created, so every delivered item is released when its window closes. Refunds never reach
  released money, because every refund window closes before release (amendment §B.8).
- Items paid before F14 was deployed have no proceeds record. Their window close grants the files and releases
  nothing.
- A release carries exactly one line. Batching several items into one transfer would need a new decision.
- What happens to a leaving member's held funds is undecided (amendment §E).
