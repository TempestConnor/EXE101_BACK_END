# Commission Quotes (F17)

Decisions recorded 2 November 2026 for the first F17 increment. The commissioned Artist offers formal terms, and the
Customer accepts or declines them. Accepting moves no money. Payment of Quotes, change Quotes (§21) and the work stages
come with the next increment, F17b.

## Approved policies

- **Payment flow (amendment §D.12–13, changed 2 November 2026):** one Quote holds the agreed total price. Accept
  Quote → pay 50% of it → sketch, then lineart, colouring and render → the Customer approves the final work → pay the
  other 50%. The sketch is part of the paid work, so the Commission has no sketch status. Under the §D.15 refund table
  the sketch counts as "paid, work not started" until lineart begins. The customer-facing Policy said "no payment
  before sketch approval", and it must be changed the same way; that text is outside this repository.
- **Status after acceptance:** the Commission moves to `AWAITING_PAYMENT` (the first 50%). No migration.
- **Initial price (Q02):** the price must be whole VND above 0. An initial Quote of 0 VND is refused with
  `400 QUOTE_INVALID`, so every Commission's work starts from a confirmed payment, as Orders do (Q01). Change Quotes of
  0 VND (§19.13, §21.8) come with F17b.
- **Tax:** the configured checkout rate (`app.checkout.tax-rate`, the [Cart](cart-checkout.md) placeholder of 0%) is
  applied to the price and rounded half-up to whole VND. The tax is fixed when the Quote is offered, so the Customer
  accepts exactly the amount shown. Amount payable = price + tax (`totalAmount`). Splitting it into two halves needs a
  rounding rule (F17b).
- **One open offer (Q03):** a Commission has at most one `OFFERED` Quote. Another offer gets `409 QUOTE_ALREADY_OFFERED`
  until the open one is withdrawn or declined.
- **Initial offer:** only while the Commission is `REQUESTED`; it becomes `QUOTED`. If the Commission is closed,
  `409 COMMISSION_CLOSED`. In any other status the offer gets `409 COMMISSION_ACTION_NOT_ALLOWED`; that covers change
  Quotes after acceptance, which come in F17b.
- **Answers:** the Customer accepts (`ACCEPTED`) or declines (`DECLINED`, and the Commission returns to `REQUESTED` so the
  Artist can quote again, §19.8–9). The Artist may withdraw an `OFFERED` Quote (`WITHDRAWN`, back to `REQUESTED`,
  §19.6). A Quote no longer `OFFERED` gets `409 QUOTE_NOT_OFFERED`. A participant who takes the other side's action gets
  `403 ACCESS_DENIED`.
- **Artist declines the request:** allowed while the Commission is `REQUESTED` or `QUOTED`. The Commission becomes
  `DECLINED`, and an open offer becomes `WITHDRAWN`. After acceptance the answer is
  `409 COMMISSION_ACTION_NOT_ALLOWED`; leaving an agreed Commission is a cancellation (F19).
- **Deactivated Artist (Q05, Quote part):** cannot offer a Quote, and the Customer cannot accept one while the Artist is
  deactivated (`409 ARTIST_NOT_ACTIVE`, amendment §A.4). Withdrawing and declining still work.
- **Terms:** scope is 1–5000 characters and completion terms 1–1000 characters (the column limit), both after
  trimming. At acceptance the zero-fee snapshot is written from the stored price: fee rate 0, fee 0, Artist proceeds =
  price (amendment §B.5). `TR_Quote_Terms_Immutable` then rejects any change to the terms or the snapshot (§19.10).
  `SequenceNo` = highest + 1, taken under the Commission lock.
- **Concurrency:** every action locks the Commission row (`UPDLOCK`) before reading its Quotes, so the actions on one
  Commission run one at a time. A racing accept and withdraw have exactly one winner, and two racing offers leave one
  `OFFERED`. Offer and accept read the Artist under the shared lock a request uses, so a deactivation either commits
  first or waits.
- **History (§23):** every action writes a `CommissionEvent` in the same transaction, with `RelatedId` = the Quote:
  `QUOTE_OFFERED` (`REQUESTED` → `QUOTED`), `QUOTE_ACCEPTED` (`QUOTED` → `AWAITING_PAYMENT`), `QUOTE_DECLINED` and
  `QUOTE_WITHDRAWN` (`QUOTED` → `REQUESTED`), and `DECLINED`. When the decline withdraws an open offer, it also writes a
  `QUOTE_WITHDRAWN` event with no status change.
- **Access:** only participants can read Quotes; anyone else gets `404 COMMISSION_NOT_FOUND`.

## HTTP contract

All endpoints need a Customer bearer token.

| Endpoint | Who | Request | Success |
| --- | --- | --- | --- |
| `GET /commissions/{id}/quotes` | participants | — | 200, the Quotes in sequence order |
| `POST /commissions/{id}/quotes` | the Artist | JSON `price`, `scopeText`, `expectedCompletionTerms` | 201 with the Quote (`status: "OFFERED"`) |
| `POST /commissions/{id}/quotes/{quoteId}/accept` | the Customer | — | 200 with the Quote (`ACCEPTED`) |
| `POST /commissions/{id}/quotes/{quoteId}/decline` | the Customer | — | 200 with the Quote (`DECLINED`) |
| `POST /commissions/{id}/quotes/{quoteId}/withdraw` | the Artist | — | 200 with the Quote (`WITHDRAWN`) |
| `POST /commissions/{id}/decline` | the Artist | — | 200 with the Commission summary (`DECLINED`) |

A Quote carries `quoteId`, `commissionId`, `sequenceNo`, `scopeText`, `expectedCompletionTerms`, `price`, `taxAmount`,
`totalAmount`, `currencyCode`, `status`, `offeredAt`, `acceptedAt` and `paidAt`.

New error codes: `QUOTE_NOT_FOUND` (404); `QUOTE_INVALID` (400); `QUOTE_ALREADY_OFFERED`, `QUOTE_NOT_OFFERED`,
`COMMISSION_ACTION_NOT_ALLOWED` (409).

## Financial correctness

No money moves in this increment, and no Payment, proceeds or ledger transaction is created. A Quote is a binding price,
so its amounts follow [financial correctness](financial-correctness.md): the server computes the tax, money is
`decimal(19,4)` whole VND, and the accepted snapshot is immutable in the database.

## Deferred to F17b

- Quote payments: two VNPay payments per Quote (50% each). **The first payment is done** in
  [Commission Quote payments](commission-quote-payments.md) (migration `0005`, first half rounded up, held proceeds,
  ledger and reconciliation); the final payment waits for F18.
- Change Quotes (§21, +10.000 VND per change, D10), including 0 VND changes, and how they are paid (amendment §E).
- The spec's F17 acceptance criteria ("a 0 VND change after a paid Quote creates no payment"; "a failed payment does
  not start work"), and the 7-day final-payment deadline (§D.14).
- The license contract on acceptance (amendment §D.17), which waits for its fields and a schema decision.

## Known limits

- **`AWAITING_PAYMENT` can now be paid** with the first 50% ([Commission Quote payments](commission-quote-payments.md)).
- **No Admin view of Quotes,** as for threads (F16), until disputes (F19).
- **The Policy text is out of date** about when the first payment is due, until the team changes it.

## Tests

`CommissionQuoteServiceTest` and `CommissionQuoteControllerTest` are unit tests. `CommissionQuotePersistenceTest` runs
only against the disposable test database selected by `TEST_DB_URL` and leaves its rows there, because Quotes can
never be deleted (`TR_Quote_Terms_Immutable`).
