# Commission refunds (F20)

Decisions recorded 2 November 2026 for the F20 increment: what happens to the held first payment of a **cancelled,
paid Commission** (§24.11–13, §25, amendment §D.15). It follows [Commission cancellation and disputes](commission-cancellation.md)
(F19), the payment rules of [Commission Quote payments](commission-quote-payments.md), and the transfer and ledger
mechanics of [Refunds](refunds.md). The money rules are
[amendment F](Marketplace_Business_Rules_Final.md#amendment-f--money-handling-1-november-2026).

## Approved policies

- **A fixed percentage under the Artist's terms (B1, amendment §D.15):** the refund is a percentage of the held money,
  set by how much work was done, under the terms the Artist publishes and both parties agreed to before the work
  started. The terms are not stored in the system. A cancelled Commission only ever has its first installment paid,
  because the final payment completes it, so the refund can never exceed the held first payment.
- **Opened automatically (B4):** a refund does not need to be asked for. Accepting a cancellation request, or a dispute
  resolved `CANCEL_REFUND`, opens a `REQUESTED` `RefundRequest` for the Customer in the same transaction, with the
  cancellation's reason. This applies only when the first installment's share is still `HELD`; a Commission cancelled
  before its first payment has nothing to refund. `UX_Refund_OpenCommission` allows one open or completed refund per
  Commission. This departs from §24.13, where the Customer asks.
- **The Artist enters the percentage (B1, R2):** a whole number from 0 to 100, entered once. A deactivated Artist can
  still do it (amendment §A.4).
- **The Customer confirms (B5):** the Customer accepts the percentage, which settles the refund at once, or objects
  with a reason of 1–5000 characters. After an objection, the Admin sets the final percentage, with a reason of
  1–2000 characters, in an audited action (`AdminAudit` `COMMISSION_REFUND_DECIDED`, target `REFUND_REQUEST`).
- **Admin fallback (R9):** the Admin may also set the final percentage while the Artist has not entered one. As the
  implementer's extension of that fallback, the Admin can also do it while the Customer has not answered, so an
  unanswered refund does not hold the Artist's money forever. The Admin's percentage is final.
- **Schema (B6, migration `0007_refund_percent.sql`):** `RefundRequest.RefundPercent` (`tinyint NULL`), with
  `CK_RefundRequest_Percent` allowing it only on a Commission request and only from 0 to 100. Order Item requests keep
  `NULL`.
- **Amounts (B2, B7, R3):** the percentage of the Artist's held share and of the first installment's tax half, each
  rounded half-up to whole VND; the refund is their sum. With the F17b example (price 100 001, tax 10 001: a held
  share of 50 001 and a tax half of 5 001), 70% refunds 35 001 + 3 501 = 38 502.
- **The rest goes to the Artist (B3, B4, R3):** in the same transaction, the proceeds `Amount` is reduced to what is
  not refunded (15 000 in the example), and that remainder is released at once with an automatic `PENDING` release, as
  at completion. The original amount remains in the Quote, the Payment and the ledger receipt, but the proceeds row
  itself is overwritten (see [Known limits](#known-limits)). At 100% the proceeds become `VOID`.
- **0% (R4):** nothing is owed back, and a transfer must be above 0. The request closes `REJECTED` and the whole share
  is released. A percentage whose amounts both round to 0 is treated the same way.
- **Transfer (R5):** one `PENDING` `RefundTransfer` against the first installment's Payment. The Admin sends it by
  hand and records the outcome with the existing `POST /admin/refund-transfers/{transferId}/status`, exactly as for
  Order Items: `SUCCEEDED` with a reference makes the request `REFUNDED` and is blocked by a reconciliation mismatch;
  `FAILED` keeps the money owed and a retry reuses the row.
- **`CANCEL_NO_REFUND` (B8):** releases the whole held share to the Artist at the resolution, and no refund is opened.
- **Lock order (R8):** the Commission row, then the refund request, then the proceeds. An Admin action reads the
  request's Commission ID first, then takes the same locks. A Customer's acceptance and an Admin decision therefore
  have exactly one winner; the loser gets `409 REFUND_ALREADY_DECIDED`.
- **No backfill (R10):** Commissions cancelled before F20 (in the test database only) keep their held share `HELD`.
- **Exception payments (R11):** money in `UNAPPLIED` is still refunded by hand and not recorded, as in F15.

## Ledger

| Event (source) | Lines |
| --- | --- |
| `REFUND_APPROVED` (RefundRequest) | `ARTIST_HELD` (Artist, Quote) +share; `TAX_PAYABLE` (Quote) +tax; `REFUND_PAYABLE` (Quote) −refund |
| `PROCEEDS_RELEASED`, `RELEASE_CREATED` | The remainder, as at completion ([Artist proceeds](artist-proceeds.md#ledger)) |
| `REFUND_PAID` (RefundTransfer) | `REFUND_PAYABLE` (Quote) +refund; `CASH` −refund |

No ledger migration: the accounts, event types and source types exist. Reconciliation's "approved refund without its
`REFUND_APPROVED` transaction" check now covers Commission requests too; the transfer checks already did. After a
partial refund, the Quote's Artist balances still match its proceeds: the reduced amount sits in `ARTIST_RELEASING`,
and nothing is left in `ARTIST_HELD`.

## HTTP contract

Participant endpoints need a Customer bearer token; Admin endpoints a staff token with the `ADMIN` role.

| Endpoint | Who | Request | Success |
| --- | --- | --- | --- |
| `GET /commissions/{id}/refund-requests` | participants | — | 200, the Commission's refunds |
| `POST /commissions/{id}/refund-requests/{refundRequestId}/percentage` | the Artist | JSON `percent` | 200, the refund (`AWAITING_CUSTOMER`) |
| `POST /commissions/{id}/refund-requests/{refundRequestId}/accept` | the Customer | — | 200, the refund (`APPROVED`, or `REJECTED` at 0%) |
| `POST /commissions/{id}/refund-requests/{refundRequestId}/objection` | the Customer | JSON `reason` | 200, the refund (`AWAITING_ADMIN`) |
| `GET /admin/commission-refund-requests?status=` | Admin | — | 200, all Commission refunds, newest first |
| `POST /admin/commission-refund-requests/{refundRequestId}/decision` | Admin | JSON `percent`, `reason` | 200, the refund (decided) |
| `POST /admin/refund-transfers/{transferId}/status` | Admin | as in [Refunds](refunds.md#api) | 200 |

A refund carries `refundRequestId`, `commissionId`, `status`, `stage` (`AWAITING_PERCENTAGE`, `AWAITING_CUSTOMER`,
`AWAITING_ADMIN`, or `null` once decided), `refundPercent`, `reason`, `requestedAt`, `decidedAt`, `decisionReason` and
`transfer`. `GET /refund-requests` also lists the Customer's Commission refunds, and its items now carry
`commissionId` and `refundPercent`.

Each step writes a `CommissionEvent` with `RelatedId` = the request: `REFUND_OPENED`, `REFUND_PERCENT_SET`
(`percent=…`), `REFUND_ACCEPTED`, `REFUND_OBJECTED` (the reason) and `REFUND_DECIDED` (staff actor).

New error codes: `REFUND_PERCENT_INVALID`, `COMMISSION_REFUND_REASON_INVALID` (400);
`COMMISSION_REFUND_ACTION_NOT_ALLOWED` (409, wrong step), `COMMISSION_REFUND_NOT_ALLOWED` (409, nothing held any
more). Also `404 REFUND_REQUEST_NOT_FOUND`, `409 REFUND_ALREADY_DECIDED`, `403 ACCESS_DENIED` for the wrong
participant, `404 COMMISSION_NOT_FOUND` for anyone else, and `400 COMMISSION_INVALID` for an invalid objection.

## Financial correctness

- The refund never exceeds the held first payment: the percentage is capped at 100, and the refunds of one payment
  are checked against its amount before the transfer is written.
- Share + remainder = the held share, exactly: the remainder is computed as held − refunded, not rounded separately.
- The decision, the transfer, the ledger posting, the proceeds change and the release commit together or not at all.
- One decision per refund: the Commission lock, the `REQUESTED` check and `UX_Refund_OpenCommission`.
- Verified against `CuratedArtworkMarketplace_Test`: exact amounts, the transfer recorded `SUCCEEDED`, a full refund, a
  `CANCEL_NO_REFUND` release, a concurrent acceptance and Admin decision, and reconciliation clean after each.

## Deferred

- **Change Quotes** (§21): a refund covers the first installment of the one accepted Quote.
- **Storing the Artist's terms** with each Quote, so the percentage can be checked against them.
- **The license contract** (amendment §D.17).

## Known limits

- **The Artist's terms are not in the system.** Nothing checks the entered percentage against them. The Customer's
  confirmation and the Admin's decision after an objection are the only checks. Closing it: a refund schedule stored
  with each Quote, as [Commission delivery](commission-delivery.md#known-limits) suggests.
- **The proceeds row is overwritten** when a refund is partial: its `Amount` becomes the remainder. The original
  amount survives only in the Quote, the Payment, the ledger receipt and the refund. Closing it: a `RefundedAmount`
  column, which needs a migration.
- **The Admin's percentage is decided by one Admin**, and its transfer recorded by one Admin, as in F15 (amendment F.8
  not implemented). The Admin can also pre-empt a Customer who has not answered yet. Closing it: a second-Admin
  confirmation.
- **A refund waits for its participants.** If neither the Artist nor the Customer acts and no Admin steps in, the
  share stays `HELD`. Closing it: a deadline after which the refund is settled automatically.
- **The release of the remainder is not checked against reconciliation**, as at the other milestones (F14 B4);
  recording a release or a refund transfer `PAID`/`SUCCEEDED` is.
- **Money behind a payment exception is refunded by hand and not recorded**, as in F15 and F19.
- The class-release limitations of amendment F.10 apply unchanged.

## Tests

- **Unit:** `CommissionRefundServiceTest`; the Commission refund cases in `LedgerServiceTest` and `ProceedsServiceTest`;
  the refund hooks in `CommissionCancellationServiceTest` and `CommissionDisputeServiceTest`;
  `CommissionRefundControllerTest` and `CommissionRefundAdminControllerTest`.
- **Persistence:** `CommissionRefundPersistenceTest` runs only against the test database selected by `TEST_DB_URL`
  and leaves its rows there. Every payment goes through the IPN, so the database stays reconcilable; the
  `CK_RefundRequest_Percent` check runs in a rolled-back transaction.
