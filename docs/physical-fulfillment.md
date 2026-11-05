# Physical fulfillment (F13)

Decisions recorded 1 November 2026 for the F13 increment.

## Approved policies

- **Actors (B1, Q06, option a):** only an active `ADMIN` staff account changes a fulfillment status. Each change is
  recorded with `FulfillmentEvent.ActorStaffId`; no schema change. Artists and `MODERATOR` accounts cannot change
  it.
- **Failure and cancellation (B2, Q06, option a):** F13 exposes neither `FAILED` nor `CANCELLED`. They are set only
  when F15 approves a refund for the item. Until then an item that cannot be made or delivered stays in its last
  state, and the money stays held.
- **Start (R1):** the payment-success transaction creates one `PENDING` fulfillment per physical Order Item, after
  the digital grant and only when the settlement is `PAID` (payment.md R5). An exception settlement creates none.
  So an unpaid Order has nothing to advance, and a transition for it is rejected with `409 FULFILLMENT_NOT_STARTED`.
- **Transitions (R2):** forward only, one step at a time:
  `PENDING → IN_PRODUCTION → READY_TO_SHIP → SHIPPED → DELIVERED`. `DELIVERED`, `FAILED` and `CANCELLED` are final;
  there are no reversals. Anything else is `409 FULFILLMENT_STATUS_CHANGE_NOT_ALLOWED`.
- **Carrier and tracking (R3):** both optional and accepted only with the move to `SHIPPED`; a tracking reference
  needs a carrier. Otherwise `400 FULFILLMENT_SHIPMENT_INVALID`, before anything is locked.
- **Delivery time (R4):** `DeliveredAt` is the server's time of the `DELIVERED` change, never a client value,
  because it starts the return window.
- **Return window (R5, amendment §C.9–10):** a job (`app.fulfillment.grant-interval`, default every 10 minutes)
  grants a delivered item's included files once `DeliveredAt` + `app.fulfillment.return-window` (default `PT48H`)
  has passed. The Order must be `PAID`, and the item must have no refund request in any status other than
  `REJECTED`. The grant reuses the F12 operation: exactly the item's `OrderItemAsset` rows, to the Order's buyer,
  once (`UQ(OrderItemId)`). Each item is granted in its own transaction, under the Order lock and then the item
  lock, and is checked again under them.
- **History and audit (R6):** each transition writes one `FulfillmentEvent` (from, to, staff, reason, time) in the
  same transaction as the status change. That is the audit record (F21); no `AdminAudit` row is added. The start
  event has no previous status and no staff actor.
- **Endpoints (R7):** see below. The buyer sees each physical item's fulfillment in `GET /orders/{orderId}` and
  `GET /orders`.
- **Locking (R8):** a transition locks the Order and then the fulfillment row, the order F15 will use, so a
  delivery and a refund decision for the same item wait for each other.

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `GET /admin/fulfillments?status=` | Admin | `[{orderItemId, orderId, status, carrier, trackingReference, deliveredAt, updatedAt, artworkTitle, listingName, specifications, quantity, shippingAddress}]`, most recently changed first; all items without `status` |
| `POST /admin/fulfillments/{orderItemId}/transitions` | Admin | Body `{status, carrier?, trackingReference?, reason?}` → the updated fulfillment, same shape |

What to make and where to send it (added 5 November 2026, from the frontend integration report): the item fields
and `shippingAddress` (`recipientName, phone, line1, line2, ward, district, provinceCode, provinceName, postalCode,
countryCode`) come from the Order's immutable snapshots, so later edits to the Customer's address book do not change
them. No prices, costs, proceeds or the Customer's email are included. The recipient's details are personal data
and are not logged.

Order items in `GET /orders/{orderId}` carry `fulfillment: {status, carrier, trackingReference, deliveredAt}`,
`null` for digital items.

Errors: `404 FULFILLMENT_NOT_FOUND` (no such item, or a digital one), `409 FULFILLMENT_NOT_STARTED`,
`409 FULFILLMENT_STATUS_CHANGE_NOT_ALLOWED`, `400 FULFILLMENT_SHIPMENT_INVALID`, `400 INVALID_REQUEST` (unknown
status), `403` for non-Admin tokens.

## Configuration

| Property | Default |
| --- | --- |
| `app.fulfillment.return-window` | `PT48H` |
| `app.fulfillment.grant-interval` | `PT10M` |

## Tests

`FulfillmentPersistenceTest` commits paid Orders, so like `PaymentPersistenceTest` it runs only against
`CuratedArtworkMarketplace_Test`, selected by `TEST_DB_URL` (see [Payment](payment.md#tests)).

## Known limits

- The Admin list is not paged and returns every matching item. Its details are read for the listed items only
  (never whole Orders), in chunks of 1000 item IDs, so no query reaches SQL Server's 2100-parameter limit however
  many lines an Order has. Paging is deferred for the class release (decided 5 November 2026): the list grows by
  one row per physical item sold. When added, it needs a decision on the list format shared by every list endpoint,
  and should use a cursor rather than an offset: advancing an item moves it out of a status filter, which would make
  offset pages skip items.

- The proceeds release at the same window close is added by F14 ([Artist proceeds](artist-proceeds.md)).
- Return requests and the refund route are F15 ([Refunds](refunds.md)): an approved refund sets an undelivered item
  to `CANCELLED`; `FAILED` is unused.
- Whether 48 hours meets consumer-protection law, and the return-shipping procedure, are open (amendment §E).
- `RefundRequest` stays read through a fulfillment-owned read-only repository: the refund service calls this one to
  cancel items, so reading through it would make the two depend on each other.
