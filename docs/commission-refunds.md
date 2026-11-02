# Commission refunds (F20)

Decisions recorded 2 November 2026 for the F20 increment: what happens to the held first payment when a **paid
Commission** is cancelled (§24.11–13, §25, amendment §D.15). It changes the cancellation flow of
[Commission cancellation and disputes](commission-cancellation.md) (F19), follows the payment rules of
[Commission Quote payments](commission-quote-payments.md), and reuses the transfer and ledger mechanics of
[Refunds](refunds.md). The money rules are
[amendment F](Marketplace_Business_Rules_Final.md#amendment-f--money-handling-1-november-2026).

## Approved policies

- **A percentage under the Artist's own terms (B1, amendment §D.15):** the refund is a percentage of the held money,
  set by how much work was done, under the terms the Artist publishes and the Customer agreed to before the work
  started. As on VGen, which the team member's terms come from, the platform does not enforce those terms: the
  percentage is part of the agreement between the two parties. A cancelled Commission only ever has its first
  installment paid, because the final payment completes it, so the refund never exceeds the held first payment.
- **The refund is agreed with the cancellation (B2, revised 2 November 2026):** on a paid Commission,
  - the **Artist's** cancellation request carries their refund percentage; the Customer accepts or rejects it;
  - the **Customer's** request carries none; the Artist answers it with a refund offer
    (`POST …/refund-offer`) or rejects it; the Customer then accepts the offer or withdraws the request.

  Only the Customer accepts, and only once an offer exists, so a paid Commission is cancelled only when both sides
  have agreed to the cancellation **and** its refund. A Customer who thinks the percentage breaks the Artist's terms
  rejects (or withdraws) and the Commission continues; the parties keep talking, or open a dispute. There is no
  separate refund step afterwards, so no refund can be left waiting.
- **The offer is a refund row (B3):** an offer is a `REQUESTED` `RefundRequest` with `RefundPercent` (migration
  `0007_refund_percent.sql`, `CK_RefundRequest_Percent`: Commission requests only, 0–100). Accepting the cancellation
  settles it; rejecting or withdrawing the request, or a final payment that overtakes it, closes it `REJECTED`, so
  declined offers stay in the history. `UX_Refund_OpenCommission` and the one pending cancellation per Commission
  keep one offer at a time. The percentage is a whole number from 0 to 100; a deactivated Artist can still offer one
  (amendment §A.4).
- **Amounts (B4, R3):** the percentage of the Artist's held share and of the first installment's tax half, each
  rounded half-up to whole VND; the refund is their sum. With the F17b example (price 100 001, tax 10 001: a held
  share of 50 001 and a tax half of 5 001), 70% refunds 35 001 + 3 501 = 38 502.
- **The rest goes to the Artist (B5, R3):** in the accepting transaction, the proceeds `Amount` is reduced to what is
  not refunded (15 000 in the example) and released at once with an automatic `PENDING` release, as at completion.
  The original amount remains in the Quote, the Payment and the ledger receipt, but the proceeds row itself is
  overwritten (see [Known limits](#known-limits)). At 100% the proceeds become `VOID`.
- **0% (R4):** nothing is owed back and a transfer must be above 0, so the refund closes `REJECTED` and the whole share
  is released. A percentage whose amounts both round to 0 is treated the same way.
- **Transfer (R5):** one `PENDING` `RefundTransfer` against the first installment's Payment. The Admin sends it by
  hand and records the outcome with the existing `POST /admin/refund-transfers/{transferId}/status`, exactly as for
  Order Items: `SUCCEEDED` with a reference makes the refund `REFUNDED` and is blocked by a reconciliation mismatch;
  `FAILED` keeps the money owed and a retry reuses the row.
- **Disputes (B6):** `CANCEL_REFUND` carries the Admin's refund percentage (required, 0–100; refused with the other
  outcomes). The refund is settled in the resolving transaction, decided by that Admin, and the dispute's
  `AdminAudit` details end with `refundPercent=…`. `CANCEL_NO_REFUND` releases the whole held share at the
  resolution. A dispute cannot be opened while a cancellation is pending, so it never meets an open offer.
- **Lock order (R8):** the Commission row, then the refund request, then the proceeds. A Customer's acceptance and a
  withdrawal or rejection of the same request have exactly one winner.
- **Earlier pending requests (R10):** a request made before F20 has no offer. The Artist can offer on a Customer's
  request; an Artist's own request must be withdrawn and made again with a percentage.
- **Exception payments (R11):** money in `UNAPPLIED` is still refunded by hand and not recorded, as in F15.

## Ledger

| Event (source) | Lines |
| --- | --- |
| `REFUND_APPROVED` (RefundRequest) | `ARTIST_HELD` (Artist, Quote) +share; `TAX_PAYABLE` (Quote) +tax; `REFUND_PAYABLE` (Quote) −refund |
| `PROCEEDS_RELEASED`, `RELEASE_CREATED` | The remainder, as at completion ([Artist proceeds](artist-proceeds.md#ledger)) |
| `REFUND_PAID` (RefundTransfer) | `REFUND_PAYABLE` (Quote) +refund; `CASH` −refund |

No ledger migration: the accounts, event types and source types exist. The Quote of a `REFUND_PAID` line is read
from its approval's `REFUND_PAYABLE` line. Reconciliation's "approved refund without its `REFUND_APPROVED`
transaction" check covers Commission refunds too; the transfer checks already did. After a partial refund the Quote's
Artist balances still match its proceeds: the reduced amount sits in `ARTIST_RELEASING`, and nothing is left in
`ARTIST_HELD`.

## HTTP contract

Participant endpoints need a Customer bearer token; Admin endpoints a staff token with the `ADMIN` role.

| Endpoint | Who | Request | Success |
| --- | --- | --- | --- |
| `POST /commissions/{id}/cancellation-requests` | participants, after the first payment | JSON `reason`, `refundPercent` (the Artist only, required) | 201 with the request |
| `POST /commissions/{id}/cancellation-requests/{cancellationId}/refund-offer` | the Artist, on the Customer's request | JSON `refundPercent` | 200 with the request |
| `POST /commissions/{id}/cancellation-requests/{cancellationId}/accept` | the Customer, once an offer exists | optional JSON `note` | 200 with the request (`ACCEPTED`) |
| `GET /commissions/{id}/refund-requests` | participants | — | 200, the Commission's offers and refunds |
| `GET /admin/commission-refund-requests?status=` | Admin | — | 200, all Commission refunds, newest first |
| `POST /admin/commission-disputes/{disputeId}/resolution` | Admin | JSON `outcome`, `reason`, `refundPercent` (with `CANCEL_REFUND`) | 200 |
| `POST /admin/refund-transfers/{transferId}/status` | Admin | as in [Refunds](refunds.md#api) | 200 |

Reject and withdraw are unchanged from F19 and close the offer. A cancellation request now also carries
`refundPercent`: the offer while it is pending, `null` before one is made. A refund carries `refundRequestId`,
`commissionId`, `status` (`REQUESTED` = offer pending), `refundPercent`, `reason`, `requestedAt`, `decidedAt`,
`decisionReason` and `transfer`. `GET /refund-requests` also lists the Customer's Commission refunds, with
`commissionId` and `refundPercent`. Each offer writes a `REFUND_OFFERED` `CommissionEvent` (`percent=…`).

New error codes: `REFUND_PERCENT_INVALID` (400: missing on the Artist's request or offer, given on the Customer's,
or outside 0–100); `COMMISSION_REFUND_ACTION_NOT_ALLOWED` (409: a second offer, an offer on the Artist's own request,
the Artist accepting, or the Customer accepting before an offer); `COMMISSION_REFUND_NOT_ALLOWED` (409: nothing held
any more). Otherwise the F19 codes apply; `CANCEL_REFUND` without a percentage is `400 DISPUTE_RESOLUTION_INVALID`.

## Financial correctness

- The refund never exceeds the held first payment: the percentage is capped at 100, and the refunds of one payment
  are checked against its amount before the transfer is written.
- Share + remainder = the held share, exactly: the remainder is computed as held − refunded, not rounded separately.
- The cancellation, the refund decision, the transfer, the ledger posting, the proceeds change and the release commit
  together or not at all.
- One settlement per offer: the Commission lock, the `PENDING` request and the `REQUESTED` refund, and
  `UX_Refund_OpenCommission`.
- Verified against `CuratedArtworkMarketplace_Test`: exact amounts for an Artist's and a Customer's request, a
  recorded transfer, a full refund, a rejected offer, both dispute outcomes, an acceptance racing a withdrawal, and
  reconciliation clean after each.

## Deferred

- ~~**The Artist's terms**~~: done in [Artist terms](artist-terms.md) (migration `0008`). Each Quote records the
  version it was accepted under, and the participants and the Admin read it with
  `GET …/quotes/{quoteId}/terms`.
- **Change Quotes** (§21): a refund covers the first installment of the one accepted Quote.
- **The license contract** (amendment §D.17).

## Known limits

- **Nothing checks the percentage against the Artist's terms.** The terms are stored and each Quote records the
  version it was accepted under ([Artist terms](artist-terms.md)), but they are free text, so the Customer's
  acceptance (or the Admin's judgement in a dispute) is the check, as on VGen. Closing it: a structured refund
  schedule per Quote that the system applies.
- **The proceeds row is overwritten** when a refund is partial: its `Amount` becomes the remainder. The original
  amount survives only in the Quote, the Payment, the ledger receipt and the refund. Closing it: a `RefundedAmount`
  column, which needs a migration.
- **A cancellation the parties never agree on leaves the money held**: the Commission simply continues, and the
  disputes path is the way out. That is the intended consequence of needing both to agree.
- **The Admin's dispute percentage and every refund transfer are recorded by one Admin** (amendment F.8 not
  implemented). Closing it: a second-Admin confirmation.
- **The release of the remainder is not checked against reconciliation**, as at the other milestones (F14 B4);
  recording a release or a refund transfer `PAID`/`SUCCEEDED` is.
- **Money behind a payment exception is refunded by hand and not recorded**, as in F15 and F19.
- The class-release limitations of amendment F.10 apply unchanged.

## Tests

- **Unit:** the offer, acceptance, rounding and closing cases in `CommissionCancellationServiceTest` (over the real
  refund services); the `CANCEL_REFUND` percentage cases in `CommissionDisputeServiceTest`; the offer closed by a
  final payment in `CommissionPaymentServiceTest`; `CommissionRefundServiceTest`; the Commission refund cases in
  `LedgerServiceTest` and `ProceedsServiceTest`; `CommissionCancellationControllerTest` and
  `CommissionDisputeAdminControllerTest`.
- **Persistence:** `CommissionRefundPersistenceTest` runs only against the test database selected by `TEST_DB_URL`
  and leaves its rows there. Every payment goes through the IPN, so the database stays reconcilable; the
  `CK_RefundRequest_Percent` and `UX_Refund_OpenCommission` checks run in rolled-back transactions.
