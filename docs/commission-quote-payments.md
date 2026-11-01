# Commission Quote payments (F17b)

Decisions recorded 2 November 2026 for the F17b increment: the **first 50% payment** of an accepted Quote, made
before the sketch (amendment §D.12–13). It moves the Commission from `AWAITING_PAYMENT` to `IN_PROGRESS` and holds the
Artist's share until the Commission's release milestone. The money rules are [amendment F](Marketplace_Business_Rules_Final.md#amendment-f--money-handling-1-november-2026),
and the payment mechanics are those of [Provider payment](payment.md).

## Approved policies

- **Scope (B1):** the first payment only. The final payment needs the Customer's approval of the finished work, which
  belongs to F18. Change Quotes, the 7-day close of an unpaid final payment (§D.14), the release of Quote proceeds and
  the license contract come later (see [Deferred](#deferred)).
- **Split (B2):** the price and the tax are each split in two, and the first half of each is rounded **up** to whole
  VND. The final payment is the remainder. Each installment therefore carries exact proceeds and tax, and the two add
  up to the Quote total. For example, price 100 001 and tax 10 001 give a first payment of 50 001 + 5 001 = 55 002 and
  a final payment of 50 000 + 5 000 = 55 000. The Customer pays the odd đồng up front, so the refund base of §D.15
  (a percentage of the first payment) is up to 1 đ larger per amount.
- **Schema (B3, migration `0005_quote_installments.sql`):** `Installment` (1 = first, 2 = final) on `Payment` and
  `ArtistProceeds`. It is `NULL` for Orders and Order Items and 1 or 2 for a Quote (`CK_Payment_Installment`,
  `CK_Proceeds_Installment`). `UX_Payment_QuoteInstallment` and `UX_Proceeds_QuoteInstallment` replace the one-per-Quote
  indexes. `LedgerEntry.QuoteId` lets the Artist accounts name a Quote. `CK_LedgerEntry_ArtistAccount` requires an
  Artist line to name exactly one of the Order Item or the Quote. No Quote Payment or Quote proceeds existed before.
- **Anomalies (B4):** no deadline for the first payment. It can be paid while the Commission is `AWAITING_PAYMENT` and
  the Quote is `ACCEPTED`. Every other provider-confirmed success is an exception, as in the [F11 table](payment.md):
  a success after that, a wrong amount, a second success or an unknown attempt. An exception never starts work or
  holds proceeds, and the amount received goes to the ledger's `UNAPPLIED` account. Each VNPay page expires
  `app.orders.payment-window` (30 minutes) after its attempt starts. Until cancellation (F19) exists, an unpaid
  Commission can wait indefinitely.
- **Endpoint (R1):** `POST /commissions/{id}/quotes/{quoteId}/payment-attempts`, for the Customer only. The Artist gets
  `403 ACCESS_DENIED`, anyone else `404 COMMISSION_NOT_FOUND`, a staff token `403`, and an unknown Quote
  `404 QUOTE_NOT_FOUND`. The IPN is the existing `GET /payments/vnpay/ipn`, which routes each payment to its Order or
  its Quote.
- **Payment row (R2):** created by the first attempt, under the Commission lock (installment 1, `PENDING`, amount =
  the first half). Later attempts reuse it. Quotes accepted under F17 needed no backfill.
- **Starting an attempt (R3):** only while the Commission is `AWAITING_PAYMENT`, the Quote is `ACCEPTED` and its first
  Payment is `PENDING`. Otherwise `409 QUOTE_NOT_PAYABLE`, and nothing is written.
- **Deactivated Artist (R4, Q05):** the Customer may still pay an accepted Quote. It is an existing commitment that
  amendment §A.4 preserves, as unexpired Orders stay payable.
- **On success (R5):** the Payment becomes `SUCCEEDED` and the Commission `IN_PROGRESS`. A `PAYMENT_CONFIRMED`
  `CommissionEvent` is written, with `RelatedId` = the Payment and the Customer as the actor. The Quote stays `ACCEPTED`:
  `CK_CommissionQuote` allows `PAID` only with a `PaidAt`, which the final payment will set.
- **Proceeds (R6):** one `HELD` record per installment, for the Artist's share of the price (fee 0, amendment §B.5).
  The first one is 50 001 in the example. It stays `HELD` until the Commission's release milestone, which comes later.
- **Ledger (R7):** `PAYMENT_RECEIVED` (source `PAYMENT`) posts `CASH` +first payment, `ARTIST_HELD` (Artist, Quote)
  −price half and `TAX_PAYABLE` (Quote) −tax half. Reconciliation now covers Quotes too:
  - a succeeded Quote Payment without its receipt;
  - Quote proceeds whose Artist balances disagree with their statuses (summed over the Quote's installments);
  - Quote balances without proceeds.
- **Responses (R8):** a Quote also carries `firstPaymentAmount` and `finalPaymentAmount`. A proceeds record also
  carries `quoteId` and `installment`.
- **License contract (R9):** not part of this increment. Its fields are undecided (amendment §D.17, §E), as in F11 B3.

## HTTP contract

| Endpoint | Who | Success |
| --- | --- | --- |
| `POST /commissions/{id}/quotes/{quoteId}/payment-attempts` | the Customer | 201 `{attemptId, commissionId, quoteId, amount, paymentUrl, expiresAt}` |

`amount` is the installment being paid. Payment progress is read through `GET /commissions/{id}` (status
`IN_PROGRESS`) and `GET /commissions/{id}/quotes`. New error code: `QUOTE_NOT_PAYABLE` (409).

## Locking

A payment start and an IPN for a Quote both lock the Commission row before the Payment. Quote actions take the
Commission lock too, so a payment cannot race an accept, withdraw or decline on the same Commission. Two successes for
one installment have exactly one winner: the second finds the Payment `SUCCEEDED` and is recorded as an exception
(§31.3). The IPN keeps the F11 order: the event key, then the attempt, then Commission or Order, then the Payment.

## Deferred

- **The final payment** (installment 2), due after the Customer approves the work (F18). With it come the Quote's
  `PAID` status, the release of the full amount at completion, and the 7-day close of an unpaid final payment, which
  releases installment 1 alone (§D.14).
- **Releasing Quote proceeds.** `ArtistMovement` and the release path only know Order Items yet.
- **Change Quotes** (§21, +10.000 VND per change, D10), including 0 VND changes, and how they are paid and refunded
  (amendment §E).
- **Cancellation and refunds** of the first payment (§D.15, F19/F20).
- **The license contract** (amendment §D.17).

## Known limits

- **An accepted Commission can wait unpaid forever.** There is no deadline for the first payment and no cancellation
  yet. Closing it: cancellation (F19), or a payment deadline decided with it.
- **Money behind a Quote exception is refunded by hand and not recorded,** as for Orders ([Refunds](refunds.md#known-limits)).
  Its amount sits in `UNAPPLIED`. Closing it: a recorded refund path for exception payments.
- **Held first payments cannot be released yet.** An `IN_PROGRESS` Commission's proceeds stay `HELD` until the release
  milestone is built. That is safe for refunds (amendment §B.8), but the Artist cannot be paid. Closing it: the
  completion and 7-day-close releases.
- **The VNPay sandbox has not been exercised,** as in [Payment](payment.md#known-limits), and the class-release
  limitations of amendment F.10 apply unchanged.

## Tests

- **Unit:** `CommissionPaymentServiceTest`, the Quote cases in `PaymentServiceTest`, `LedgerServiceTest` and
  `ProceedsServiceTest`, `QuotePaymentAttemptControllerTest`, and the installment amounts in
  `CommissionQuoteControllerTest`.
- **Persistence:** `QuotePaymentPersistenceTest` runs only against `CuratedArtworkMarketplace_Test`, selected by
  `TEST_DB_URL`. It leaves its rows there, because Quotes cannot be deleted. Every committed payment writes its
  ledger receipt, so the database stays reconcilable. The installment-constraint checks run in a rolled-back
  transaction.
