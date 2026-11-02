# Commission final payment and completion (F18b)

Decisions recorded 2 November 2026 for the F18b increment: the **final 50% payment** of an approved Commission, its
completion, unlocking the approved files and releasing the Quote's proceeds (amendment §D.13). It follows the delivery
loop of [Commission delivery](commission-delivery.md) (F18) and the payment mechanics of
[Commission Quote payments](commission-quote-payments.md) (F17b). The money rules are
[amendment F](Marketplace_Business_Rules_Final.md#amendment-f--money-handling-1-november-2026).

## Approved policies

- **No final-payment deadline (B1, B2, rule change):** the 7-day close of an unpaid final payment (amendment §D.14) is
  removed. An approved Commission stays `AWAITING_PAYMENT` until the Customer pays, and the Customer and the Artist
  settle an unpaid Commission between them under the terms they agreed to. There is no close job and no lateness:
  a final payment is accepted whenever it arrives while the Commission is `AWAITING_PAYMENT`. No migration. See
  [Known limits](#known-limits) for the weakness.
- **0 VND final installment (B3):** with the first half rounded up, a Quote of 1 VND with no tax leaves a final
  installment of 0 VND, which cannot be paid (`Payment.Amount > 0`). Approval then completes the Commission at once,
  as a 0 VND Quote binds without payment (§19.13): the Quote becomes `PAID`, the Commission `COMPLETED` (the
  `DELIVERABLE_APPROVED` event goes to `COMPLETED`), no final Payment is created, and the first installment is released.
- **Endpoint (R1):** the existing `POST /commissions/{id}/quotes/{quoteId}/payment-attempts` pays the final installment
  too. While the Commission is `AWAITING_PAYMENT` and the Quote `ACCEPTED`, the installment is the first one unless
  installment 1 is already `SUCCEEDED`, which only happens after approval. The response gains `installment`.
  The access rules of F17b R1 apply unchanged.
- **Final Payment (R2):** created by its first attempt, under the Commission lock, as installment 2, `PENDING`, for the
  remainder of the price and the tax (F17b B2): 50 000 + 5 000 = 55 000 in the F17b example. Later attempts reuse it.
  Each VNPay page expires `app.orders.payment-window` (30 minutes) after its attempt starts.
- **On success (R3, §D.13):** the Payment becomes `SUCCEEDED`, the Quote `PAID` with `PaidAt`, the Commission
  `COMPLETED` with `CompletedAt`, all at the receipt time. One `PAYMENT_CONFIRMED` event (`AWAITING_PAYMENT →
  COMPLETED`, the Customer as actor). The ledger receipt and the installment-2 proceeds (`HELD`) are written as in
  F17b R6–R7.
- **Anomalies (R4):** a wrong amount, a second success for installment 2, or a success when the Commission is no longer
  `AWAITING_PAYMENT` is an exception, as in F17b B4: nothing changes on the Commission, and the amount goes to
  `UNAPPLIED`. Two successes race under the Commission lock, and one wins.
- **Release at completion (R5, §D.13, F14 B1/B2):** in the completing transaction, after the hold, each installment's
  proceeds become `ELIGIBLE` with `EligibleAt` = the completion time, and each gets its own automatic `PENDING`
  release (`RESERVED`). 0 VND proceeds are settled `PAID` without a release. Proceeds already released are skipped
  under the Commission lock. The Admin then transfers and records each release as for Order Items.
- **Ledger (R6):** `PROCEEDS_RELEASED`, `RELEASE_CREATED`, `RELEASE_PAID` and `RELEASE_FAILED` lines for Quote proceeds
  name the Quote instead of an Order Item. Reconciliation's "released proceeds without their entry" check now covers
  Quote proceeds too.
- **Files (R7):** once the Commission is `COMPLETED`, the Customer can download the files of the approved version, the
  latest one. Earlier versions stay locked for the Customer (`403 DELIVERABLE_LOCKED`); the Artist downloads every
  version as before.
- **Deactivated Artist (R8):** the final payment stays payable and the releases are created, as for the first payment
  (F17b R4) and as [Artist deactivation](artist-deactivation.md) leaves held funds unchanged.

## HTTP contract

| Endpoint | Who | Success |
| --- | --- | --- |
| `POST /commissions/{id}/quotes/{quoteId}/payment-attempts` | the Customer | 201 `{attemptId, commissionId, quoteId, installment, amount, paymentUrl, expiresAt}` |
| `GET /commissions/{id}/deliverables/{deliverableId}/files/{fileId}/content` | the Artist; the Customer for the approved version of a `COMPLETED` Commission | 200, the file streamed |

No new error codes. `409 QUOTE_NOT_PAYABLE` when no installment is due, `403 DELIVERABLE_LOCKED` for a locked file.

## Financial correctness

- The two installments add up to the Quote total, and each carries its exact proceeds and tax (F17b B2).
- Installment 2 is paid once: the Commission lock, the Payment status and `UX_Payment_QuoteInstallment`.
- Completion, the Quote's `PAID` status, the receipt, the hold and both releases commit together or not at all.
- Each installment is released once: the Commission lock, the `HELD` filter and `UX_PayoutLine_Active`.
- Verified against `CuratedArtworkMarketplace_Test`: exact amounts, duplicate and concurrent IPNs, a release recorded
  `PAID`, and reconciliation clean after each.

## Deferred

- **Change Quotes** (§21, D10). Completion assumes the Commission's one accepted Quote.
- **Refunds** (F20), including how the held first payment of a cancelled Commission is settled. Cancellation and
  disputes are F19 ([Commission cancellation and disputes](commission-cancellation.md)).
- **The review preview** (format undecided, amendment §E) and **the license contract** (amendment §D.17).

## Known limits

- **An approved Commission can wait unpaid forever.** Its first installment stays `HELD`, so the Artist is not paid
  for finished work, and the Customer cannot download it. The parties can now cancel it (F19), but the system has no
  recorded way to release or refund the held first payment until F20. Closing it: F20's refund decision, or a
  final-payment deadline.
- **`AWAITING_PAYMENT` still means two things:** the first payment is due, or the final one is (installment 1
  `SUCCEEDED`). The payment endpoint tells them apart; clients read the Quote's payments. Closing it: a separate status,
  which needs a migration.
- **No protected preview** ([Commission delivery](commission-delivery.md#known-limits)): the Customer pays the final
  50% without having seen the files in the system.
- **Money behind a Quote exception is refunded by hand and not recorded**, as in F17b.
- **The automatic releases at completion are not checked against reconciliation**, as at the digital and physical
  milestones (F14 B4); recording a release `PAID` is.
- The class-release limitations of amendment F.10 apply unchanged.

## Tests

- **Unit:** the final-payment cases in `CommissionPaymentServiceTest`, the zero-final approval and file cases in
  `CommissionDeliveryServiceTest`, the Quote release cases in `ProceedsServiceTest`, the final-installment cases in
  `PaymentServiceTest`, and `QuotePaymentAttemptControllerTest`.
- **Persistence:** `CommissionCompletionPersistenceTest` runs only against the test database selected by
  `TEST_DB_URL` and leaves its rows there. Every payment goes through the IPN, so the database stays reconcilable; the
  missing-entry check runs in a rolled-back transaction.
