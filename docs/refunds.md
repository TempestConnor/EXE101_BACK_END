# Order item refunds and returns (F15)

Decisions recorded 1 November 2026 for the F15 increment. Order Items only; Commission refunds belong to priority 3.
The money rules these follow are [amendment F](Marketplace_Business_Rules_Final.md#amendment-f--money-handling-1-november-2026)
of the business rules; the ledger is described in [Artist proceeds](artist-proceeds.md#ledger).

## Approved policies

- **Refund amount (B1, Q07, option "tax in; ship at approval"):** an approved refund returns everything the buyer paid
  for the item: its line total plus its own tax (the Order's tax is the sum of item taxes, so nothing else is
  allocated). Physical items only; there is never a partial refund, whatever the quantity (§16.4–5).
- **Shipping (B1, Q07):** the Order's one shipping charge is refunded with the approval that leaves no physical item of
  the Order unrefunded. An item counts as refunded once its request is `APPROVED` or `REFUNDED`, whatever its transfer's
  state. That approval writes the `ShippingRefund` row, and the shipping is added to its own transfer. `ShippingRefund`
  is keyed by the Order, so the shipping is refunded at most once; an Order with 0 shipping writes no row.
- **Transfers (B2, Q13, option "manual, reuse row"):** the Admin returns the money outside the system, through the
  VNPay merchant portal or a bank transfer, and records the outcome:
  - `PENDING → SUCCEEDED` needs the transfer reference, stored as `ProviderName = MANUAL` with `ProviderRefundRef`;
    `UX_Refund_ProviderRef` makes one transfer settle one refund. The request becomes `REFUNDED`. `SUCCEEDED` is final.
  - `PENDING → FAILED` keeps the money owed; the request stays `APPROVED`.
  - `FAILED → PENDING` retries on the same row, because the schema allows one transfer per request and payment. The
    failure history is kept in `AdminAudit`, not in the row.
  - A timeout is never treated as a failure: the Admin records an outcome only after checking the bank or portal.
- **Ledger (B3, schema approved 1 November 2026, migration `0003_refund_ledger.sql`):** adds the `REFUND_APPROVED` and
  `REFUND_PAID` events, the `REFUND_REQUEST` and `REFUND_TRANSFER` sources, and the `REFUND_PAYABLE` account. Refunds
  of F11 exception payments (`UNAPPLIED`) are **not** recorded by the system; they stay a manual, declared gap.
- **Endpoints (R1):** see the API below. Only the buyer requests and lists their own requests; only an active `ADMIN`
  decides and records transfers.
- **Who and when (R2, §16.11, amendment §C.9):** the buyer of a paid Order, for a physical item whose fulfillment has
  started and is not `CANCELLED`/`FAILED`, and whose included files are not granted. A delivered item can be requested
  only before `DeliveredAt` + `app.fulfillment.return-window` (server time). A digital item is not refundable after
  payment (§5.7). One open or completed request per item (`UX_Refund_OpenItem`); after a rejection the item can be
  requested again while it is still eligible.
- **Approval (R3):** in one transaction, under the Order lock, then the fulfillment lock, then the proceeds lock (the
  order the window close takes them), everything is checked again before anything is written: the Order is paid, the
  item still refundable for the request's time, its proceeds still `HELD` and its files not granted. Then the request
  becomes `APPROVED`, an undelivered item's fulfillment becomes `CANCELLED` with a `FulfillmentEvent` (a delivered item
  stays `DELIVERED`, because `DeliveredAt` belongs to that status), the proceeds become `VOID`, the shipping refund is
  written if due, a `PENDING` transfer is created against the Order's payment, the ledger is posted, and an
  `AdminAudit` row `REFUND_APPROVED` (target `REFUND_REQUEST`) records the staff member, the reason and the amounts.
  The refunds of one payment can never add up to more than it.
- **Statuses (R4):** requests `REQUESTED → APPROVED | REJECTED`, then `APPROVED → REFUNDED`. `PROCESSING` and request
  `FAILED` are unused. Fulfillment `FAILED` is unused; approval always uses `CANCELLED`.
- **Rejection (R5):** needs a reason, and writes `AdminAudit` `REFUND_REJECTED`. The next window-job run grants the files
  and releases the proceeds as usual (amendment §B.7).
- **Reconciliation (R6):** extended with the refund checks below. A mismatch blocks recording a transfer `SUCCEEDED`
  (`409 RECONCILIATION_MISMATCH`), as it blocks recording a release `PAID`; `FAILED` and a retry stay allowed.
- **Return shipping (R7):** not modelled. The Admin handles the physical return outside the system and records it in
  the decision reason (amendment §E).
- **Order view (R8):** each item in `GET /orders` and `GET /orders/{orderId}` carries `refundStatus`, the latest
  request's status, `null` when there is none.

## Ledger

| Event (source) | Lines |
| --- | --- |
| `REFUND_APPROVED` (RefundRequest) | `ARTIST_HELD` +proceeds; `MANUFACTURER_PAYABLE` +quantity × cost; `TAX_PAYABLE` +tax; `SHIPPING` +shipping when refunded; `REFUND_PAYABLE` −refund amount |
| `REFUND_PAID` (RefundTransfer) | `REFUND_PAYABLE` +amount; `CASH` −amount |

A failed transfer writes nothing, so the amount stays in `REFUND_PAYABLE` until a retry succeeds. The lines must add up
to the refund amount; a snapshot whose parts disagree with its total rolls the approval back.

Reconciliation adds: approved or refunded requests without their `REFUND_APPROVED` transaction and the reverse; a
transfer whose amount differs from what its approval made owed; succeeded transfers without `REFUND_PAID` and the
reverse; a `REFUND_PAID` whose cash differs from its transfer. `VOID` proceeds must have nothing held.

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `POST /orders/{orderId}/items/{orderItemId}/refund-requests` | Customer (buyer) | Body `{reason}` → `201`, the request |
| `GET /refund-requests` | Customer | The caller's requests, newest first |
| `GET /admin/refund-requests?status=` | Admin | All Order Item requests, newest first; all without `status` |
| `POST /admin/refund-requests/{refundRequestId}/decision` | Admin | Body `{decision: APPROVE\|REJECT, reason?}` → the request |
| `POST /admin/refund-transfers/{transferId}/status` | Admin | Body `{status, transferReference?, reason?}` → the request |

A request is `{refundRequestId, orderItemId, status, reason, requestedAt, decidedAt, decisionReason, shippingAmount,
transfer: {transferId, amount, currencyCode, status, transferReference, createdAt, succeededAt}}`.

Errors:

- `404 ORDER_ITEM_NOT_FOUND` (no such item in the caller's Order), `404 REFUND_REQUEST_NOT_FOUND`,
  `404 REFUND_TRANSFER_NOT_FOUND`
- `409 REFUND_NOT_ALLOWED`, `409 REFUND_REQUEST_OPEN`, `409 REFUND_ALREADY_DECIDED`,
  `409 REFUND_TRANSFER_STATUS_CHANGE_NOT_ALLOWED`, `409 REFUND_REFERENCE_IN_USE`, `409 RECONCILIATION_MISMATCH`
- `400 REFUND_DECISION_REASON_REQUIRED`, `400 REFUND_REFERENCE_INVALID`, `400 INVALID_REQUEST`
- `403` for the wrong token type or a non-Admin staff account

## Tests

`RefundPersistenceTest` commits paid Orders, so like `PaymentPersistenceTest` it runs only against
`CuratedArtworkMarketplace_Test`, selected by `TEST_DB_URL` (see [Payment](payment.md#tests)). Apply
`0003_refund_ledger.sql` to the test database too, or recreate it from the development database.

## Known limits

- **Manufacturing cost on a refunded print.** Approval reverses `MANUFACTURER_PAYABLE`, which assumes the manufacturer
  is not paid for a print that was refunded. The system cannot know whether a print was already made and paid for, and
  with no Platform Fee nobody's money is set aside to absorb that loss. Closing it: record manufacturer payments, and
  decide who bears the cost of a print refunded after production.
- **Exception payments are refunded by hand.** Money behind an F11 exception stays in `UNAPPLIED`; the system records no
  refund of it. Closing it: an Admin action that records such a refund against its `ProviderEvent`, with its own
  ledger event and schema decision.
- **Refund transfers are made by hand**, like releases, with one Admin recording the outcome alone (amendment F.8 is not
  implemented). Closing it: the VNPay refund API under a production merchant contract, and a second-Admin
  confirmation.
- **Failure history lives in the audit only**, because a retry reuses the transfer row.
- Items paid before F14 have no proceeds record, so their refunds cannot be posted consistently; approval rejects them
  with `409 REFUND_NOT_ALLOWED`.
- Whether 48 hours satisfies consumer-protection law, and the return-shipping procedure, are open (amendment §E).
