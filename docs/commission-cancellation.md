# Commission cancellation and disputes (F19)

Decisions recorded 2 November 2026 for the F19 increment: **cancelling a Commission** before and after the first
payment, and **disputes** the Admin reviews and resolves (§24, §26). No money moves in this increment: refunds after a
cancellation are F20. It follows the payment rules of [Commission Quote payments](commission-quote-payments.md) and
[Commission final payment and completion](commission-completion.md). The money rules are
[amendment F](Marketplace_Business_Rules_Final.md#amendment-f--money-handling-1-november-2026).

## Approved policies

- **No money moves (B1):** every cancellation, agreed or forced by a dispute, leaves each paid installment's proceeds
  `HELD`. Nothing is refunded, released or posted to the ledger. F20 decides the refund and releases the rest. See
  [Known limits](#known-limits) for the weakness.
- **Disputes use the `DISPUTED` status (B2):** opening a dispute moves the Commission to `DISPUTED`, and the
  `DISPUTE_OPENED` event keeps the status it interrupted. While `DISPUTED`, every formal participant action (Quote
  answers, submissions, approval, revision requests, cancellation requests) and every new payment attempt is refused.
  Messages and attachments still work (§24.7, §26.9). A VNPay success already in flight is an exception, booked to
  `UNAPPLIED` as in F17b B4. Its Payment stays `PENDING`, so the installment can be paid again after `CONTINUE`.
- **Cancelling before the first payment (B3, amendment §D.12):** either participant cancels at once with a reason, no
  request needed, while the Commission is `REQUESTED`, `QUOTED` or `AWAITING_PAYMENT` with no installment paid. An open
  offer is withdrawn and a `PENDING` Payment becomes `CANCELLED`. Its provider attempts stay open, so a success that
  still lands is an exception booked to `UNAPPLIED`, refunded by hand and not recorded (as in F17b).
- **Requesting cancellation (R1, §24.1–2):** either participant, on a paid Commission (`IN_PROGRESS`,
  `SUBMITTED_FOR_REVIEW`, `REVISION_REQUESTED`, or `AWAITING_PAYMENT` after approval), with a reason of 1–5000
  characters. One request may be pending at a time (`UX_Cancellation_Pending`). Before the first payment the request
  is refused: cancel directly instead. After it, cancelling directly gets `409 CANCELLATION_REQUEST_REQUIRED`.
- **While a request is pending (R2, §24.7–8):** Quote offers, submissions, approval, revision requests and new payment
  attempts get `409 CANCELLATION_PENDING`. Messages, the answer and the withdrawal still work.
- **Answers (R3, §24.3–6, §24.9, §31.9):** only the other participant accepts or rejects, and only the requester
  withdraws. Each happens once (`409 CANCELLATION_NOT_PENDING` afterwards). An optional note of up to 5000 characters
  is kept in the event's `Details`, since `CancellationRequest` has no column for it. Acceptance cancels the
  Commission, and a `PENDING` final-installment Payment becomes `CANCELLED`. Rejected and withdrawn requests stay in
  the history.
- **Opening a dispute (R4, §26.1–2):** either participant, on a Commission that is not closed, with a reason of
  1–5000 characters. At most one active dispute: the Commission lock, the `DISPUTED` status and `UX_Dispute_Active`.
  It is refused while a cancellation request is pending (reject or withdraw it first), so no request is left pending
  under a dispute.
- **Admin review (R5, §26.3–7, §29):** staff Admin only. `OPEN → UNDER_REVIEW`, then `RESOLVED` with an outcome and a
  reason of 1–2000 characters (the length of `AdminAudit.Reason`). Each step writes an `AdminAudit` row and a
  `CommissionEvent` with the staff actor in the same transaction. `CONTINUE` restores the interrupted status;
  `CANCEL_REFUND` and `CANCEL_NO_REFUND` both cancel the Commission and differ only in the recorded outcome until F20.
  The reason is returned as `resolutionReason` from the resolving event.
- **Evidence (R6, §26.9):** participants list their Commission's cancellation requests and disputes. The Admin lists
  disputes, optionally by status, and reads one dispute with its Commission, messages, Quotes, versions (metadata and
  revision requests) and cancellation requests.
- **Deactivated Artist (R7, amendment §A.4):** keeps every cancellation and dispute action.
- **Concurrency (R8, §31.4, §31.8–9):** every action locks the Commission row (`UPDLOCK`) first. An Admin action reads
  the dispute's Commission ID, locks the Commission, then reads the dispute, the same order as the participants. No
  migration.
- **Final payment overtaking a pending request (implementer's choice):** a payment page opened before the request was
  made can still be paid. The success completes the Commission as usual, and the pending request is closed as
  `REJECTED` with a `CANCELLATION_CLOSED` event (the Customer as actor). Accepting it afterwards gets
  `409 CANCELLATION_NOT_PENDING`. If the acceptance commits first, the success is an exception.

## HTTP contract

Participant endpoints need a Customer bearer token; Admin endpoints a staff token with the `ADMIN` role.

| Endpoint | Who | Request | Success |
| --- | --- | --- | --- |
| `POST /commissions/{id}/cancel` | participants, before the first payment | JSON `reason` | 200 with the Commission summary (`CANCELLED`) |
| `GET /commissions/{id}/cancellation-requests` | participants | — | 200, requests in order |
| `POST /commissions/{id}/cancellation-requests` | participants, after the first payment | JSON `reason` | 201 with the request (`PENDING`) |
| `POST /commissions/{id}/cancellation-requests/{cancellationId}/accept` | the other participant | optional JSON `note` | 200 with the request (`ACCEPTED`) |
| `POST /commissions/{id}/cancellation-requests/{cancellationId}/reject` | the other participant | optional JSON `note` | 200 with the request (`REJECTED`) |
| `POST /commissions/{id}/cancellation-requests/{cancellationId}/withdraw` | the requester | — | 200 with the request (`WITHDRAWN`) |
| `GET /commissions/{id}/disputes` | participants | — | 200, disputes in order |
| `POST /commissions/{id}/disputes` | participants | JSON `reason` | 201 with the dispute (`OPEN`) |
| `GET /admin/commission-disputes?status=` | Admin | — | 200, disputes in order |
| `GET /admin/commission-disputes/{disputeId}` | Admin | — | 200 `{dispute, commission, messages, quotes, deliverables, cancellationRequests}` |
| `POST /admin/commission-disputes/{disputeId}/review` | Admin | — | 200 with the dispute (`UNDER_REVIEW`) |
| `POST /admin/commission-disputes/{disputeId}/resolution` | Admin | JSON `outcome` (`CONTINUE`, `CANCEL_REFUND`, `CANCEL_NO_REFUND`), `reason` | 200 with the dispute (`RESOLVED`) |

A request carries `cancellationId`, `commissionId`, `requestedByUserId`, `reason`, `status`, `createdAt` and
`resolvedAt`. A dispute carries `disputeId`, `commissionId`, `openedByUserId`, `reason`, `status`, `outcome`,
`decidedByStaffId`, `resolutionReason`, `openedAt` and `resolvedAt`.

New error codes: `CANCELLATION_REQUEST_REQUIRED`, `CANCELLATION_PENDING`, `CANCELLATION_NOT_PENDING`,
`DISPUTE_ALREADY_OPEN`, `DISPUTE_ACTION_NOT_ALLOWED` (409); `CANCELLATION_REQUEST_NOT_FOUND`, `DISPUTE_NOT_FOUND` (404);
`DISPUTE_RESOLUTION_INVALID` (400). Invalid participant reasons: `400 COMMISSION_INVALID`; wrong participant:
`403 ACCESS_DENIED`; wrong status: `409 COMMISSION_ACTION_NOT_ALLOWED` or `409 COMMISSION_CLOSED`.

## Financial correctness

F19 records no money event, but it changes when Quote payments apply:

- A cancelled Commission takes no payment: its awaited Payment is `CANCELLED`, and a late success is an exception
  booked to `UNAPPLIED` (`late_success_after_prepayment_cancel_is_unapplied_ok`).
- A `DISPUTED` Commission takes no payment, and its Payment stays payable for a `CONTINUE`
  (`quote_payment_success_on_disputed_commission_ko`).
- An acceptance and a final payment race under the Commission lock, and exactly one wins
  (`accept_cancellation_vs_final_payment_one_wins_ok`).
- Cancelled paid Commissions keep their proceeds `HELD`, with no release and a clean reconciliation
  (`cancel_paid_commission_keeps_proceeds_held_ok`, `dispute_cancel_outcome_keeps_proceeds_held_ok`).

## Deferred

- **Commission refunds (F20):** the refund request after cancellation, the Admin's check of the agreed amount, the
  refund transfers, and releasing whatever is not refunded. A `CANCEL_REFUND` outcome does not create a refund yet.
- **Change Quotes** (§21): cancellation while a change Quote awaits payment.
- **Admin download of Commission files** as evidence: the Admin sees file names and sizes only.
- **The license contract** (amendment §D.17).

## Known limits

- **A cancelled paid Commission holds its money indefinitely until F20 exists.** The Artist is not paid for the work
  done and the Customer is not refunded; the held installment shows as `HELD` in the ledger and in the proceeds.
  Closing it: F20's refund decision, which releases what is not refunded.
- **`CANCEL_REFUND` and `CANCEL_NO_REFUND` have the same effect today.** Only the recorded outcome differs. Closing
  it: F20 acting on the outcome.
- **Money behind a payment exception is refunded by hand and not recorded**, as in F17b: a success on a cancelled or
  `DISPUTED` Commission sits in `UNAPPLIED`. After `CONTINUE` the Customer may pay the same installment again, so the
  money is held twice until the exception is refunded. Closing it: recorded refunds of exception payments.
- **No conflict-of-interest check:** an Admin who is also the disputed Artist can resolve the dispute (amendment §E).
  Staff accounts and Users are separate, so the system cannot tell. Closing it: linking staff accounts to Users, or a
  second reviewer.
- **The Admin's actions are not confirmed by a second Admin** (amendment F.8); no money moves here, but a cancelling
  outcome decides the fate of held funds for F20.
- **Not every rejection is stored:** the answer note lives only in the event's `Details`, and a request closed by the
  final payment is recorded as `REJECTED`, because the status set has no better value. Closing it: columns for the
  responder and a `CLOSED` status, which need a migration.

## Tests

- **Unit:** `CommissionCancellationServiceTest`, `CommissionDisputeServiceTest`; the guard cases in
  `CommissionQuoteServiceTest`, `CommissionDeliveryServiceTest`, `CommissionPaymentServiceTest` and
  `CommissionServiceTest`; `CommissionCancellationControllerTest` and `CommissionDisputeAdminControllerTest`.
- **Persistence:** `CommissionCancellationPersistenceTest` runs only against the test database selected by
  `TEST_DB_URL` and leaves its rows there. Every payment goes through the IPN, so the database stays reconcilable.
