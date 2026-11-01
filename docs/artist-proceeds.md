# Artist proceeds, releases and the ledger (F14)

Decisions recorded 1 November 2026 for the F14 increment. Order Items only; Quote proceeds belong to priority 3.
The money rules these implement, and the class release's declared limitations, are
[amendment F](Marketplace_Business_Rules_Final.md#amendment-f--money-handling-1-november-2026) of the business rules.

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

- **Ledger (B3, schema approved 1 November 2026, migration `0002_ledger.sql`):** every money event writes one
  `LedgerTransaction` with `LedgerEntry` lines, in the same database transaction as the event (the ledger service
  requires one). Debits are positive, credits negative, and each transaction's lines add up to 0; an unbalanced
  posting is rejected before anything is written, so a non-zero fee, which has no account to go to, rolls the whole
  payment back. `UX_LedgerTransaction_Source (Type, SourceType, SourceId)` records each event once, and
  `TR_LedgerTransaction_Immutable` / `TR_LedgerEntry_Immutable` reject every UPDATE and DELETE. `ArtistProceeds` and
  `Payout` keep their statuses as the working records and locks; the ledger is the money history they must agree
  with (the Stripe Ledger model).
- **Reconciliation blocks releases (B4):** while reconciliation finds any mismatch, the Admin cannot start a release
  or record one as `PROCESSING` or `PAID` (`409 RECONCILIATION_MISMATCH`). Recording `FAILED` keeps the money in and
  stays allowed. The release created automatically at a milestone is not checked: it moves no money (that needs a
  `PAID` record, which is blocked), and a full reconciliation inside the payment or window-close transaction would
  hold their locks for the whole scan.

## Ledger

| Account | Holds |
| --- | --- |
| `CASH` | Money received through VNPay, less releases paid |
| `ARTIST_HELD` (Artist, item) | Proceeds owed while the item's refund window is open |
| `ARTIST_AVAILABLE` (Artist, item) | Proceeds released after a failed release, waiting for a new one |
| `ARTIST_RELEASING` (Artist, item) | Proceeds in an open release; a clearing account that returns to 0 when it settles |
| `MANUFACTURER_PAYABLE` | Quantity × manufacturing cost of each print |
| `SHIPPING`, `TAX_PAYABLE` | The Order's shipping and each item's tax |
| `UNAPPLIED` | Exception payments (wrong amount, late, second success, unknown attempt), until refunded by hand |

| Event (source) | Lines |
| --- | --- |
| `PAYMENT_RECEIVED` (Payment) | `CASH` +total; per item `ARTIST_HELD` −proceeds, `TAX_PAYABLE` −tax, `MANUFACTURER_PAYABLE` −quantity × cost; `SHIPPING` −shipping |
| `PAYMENT_UNAPPLIED` (ProviderEvent) | `CASH` +amount received; `UNAPPLIED` −the same |
| `PROCEEDS_RELEASED` (ArtistProceeds) | `ARTIST_HELD` +x; `ARTIST_AVAILABLE` −x |
| `RELEASE_CREATED` (Payout) | `ARTIST_AVAILABLE` +x; `ARTIST_RELEASING` −x |
| `RELEASE_PAID` (Payout) | `ARTIST_RELEASING` +x; `CASH` −x |
| `RELEASE_FAILED` (Payout) | `ARTIST_RELEASING` +x; `ARTIST_AVAILABLE` −x |

Lines of 0 are left out, so 0 VND proceeds write nothing. Reconciliation (`GET /admin/finance/reconciliation`)
returns the balance of every account and the mismatches of five checks: unbalanced transactions; proceeds whose
Artist balances disagree with their status (`HELD` ↔ held, `ELIGIBLE` ↔ available, `RESERVED` ↔ releasing, `PAID`
↔ nothing owed); records without their ledger transaction (succeeded payment, exception event, released proceeds,
release, paid or failed release); ledger transactions without their record; and a payment whose cash differs from
its amount.

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `GET /proceeds` | Customer (Artist) | `[{proceedsId, orderItemId, amount, currencyCode, status, eligibleAt, paidAt}]`, newest first |
| `GET /proceeds/releases` | Customer (Artist) | The caller's releases, newest first (shape below) |
| `GET /admin/releases?status=` | Admin | `[{payoutId, artistId, proceedsId, amount, currencyCode, status, transferReference, createdAt, paidAt}]`, newest first; all without `status` |
| `POST /admin/releases/{payoutId}/status` | Admin | Body `{status, transferReference?, reason?}` → the updated release |
| `POST /admin/proceeds/{proceedsId}/releases` | Admin | `201`, the new `PENDING` release for `ELIGIBLE` proceeds |
| `GET /admin/finance/reconciliation` | Admin | `{clean, balances: [{account, amount}], mismatches: [{check, recordType, recordId}]}` |

Errors:

- `404 RELEASE_NOT_FOUND`, `404 PROCEEDS_NOT_FOUND`
- `409 RELEASE_STATUS_CHANGE_NOT_ALLOWED`, `409 PROCEEDS_NOT_RELEASABLE`, `409 RELEASE_REFERENCE_IN_USE`,
  `409 RECONCILIATION_MISMATCH`
- `400 RELEASE_REFERENCE_INVALID`, `400 INVALID_REQUEST` (unknown status)
- `403` for the wrong token type

## Tests

`ProceedsPersistenceTest` and `LedgerPersistenceTest` commit paid Orders, so like `PaymentPersistenceTest` they run
only against `CuratedArtworkMarketplace_Test`, selected by `TEST_DB_URL` (see [Payment](payment.md#tests)).

Because a single mismatch anywhere blocks releases, every persistence test must leave the shared test database
reconcilable: a fixture that writes a succeeded payment directly also writes its ledger receipt, and a paid Order
always has items that account for its subtotal and tax. Apply each migration to the test database too, or recreate
it from the development database with `database/test/reset_test_database.sql`.

## Known limits

- Refunds are F15 ([Refunds](refunds.md)): an approved refund sets held proceeds to `VOID` and reverses them in the
  ledger, and an open request withholds the physical release. Refunds never reach released money, because approval
  requires the proceeds to be `HELD` (amendment §B.8).
- Items paid before F14 was deployed have no proceeds record. Their window close grants the files and releases
  nothing.
- A release carries exactly one line. Batching several items into one transfer would need a new decision.
- What happens to a leaving member's held funds is undecided (amendment §E).
- The weaknesses of the class release are declared in amendment F.10: no real money moves, Artist releases are
  transferred by hand, `CASH` is not compared with a bank statement, and the second-person check (F.8) and release
  limits (F.9) are not implemented yet.
- Reconciliation reads whole tables on each check, and runs before every Admin release action. That is fine at the
  class release's size; a larger system would reconcile on a schedule and keep the result.
- Recording a release `PAID` checks reconciliation, then locks the release; a mismatch that appears between the
  two is caught by the next check, not by this one.
