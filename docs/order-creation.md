# Order creation and immutable snapshots (F10)

Decisions recorded 31 October 2026 for the F10 increment.

## Approved policies

- **One transaction (§9.12–13, §31.10):** `POST /orders` claims the Cart, locks its Listings, re-checks
  availability and recomputes the checkout preview under those locks, compares it with what the Customer confirmed,
  and then writes the Order, its items, their files, the shipping snapshot and the payment target. Either all of
  these are written or none are.
- **What the Customer confirms (F09 B1):** the request sends back the preview's `cartVersion`, each line's
  `listingId`, `quantity` and `unitPrice`, and `totalAmount`. The server computes every amount itself. A stale Cart
  version gets `409 CART_CONFLICT`; a different set of lines, quantity, unit price or total gets
  `409 CHECKOUT_CHANGED` with the fresh preview in `details`. Comparing the total also catches a changed tax rate
  or shipping fee.
- **Blocked checkout:** any preview problem (`CART_EMPTY`, a line problem such as `LISTING_UNAVAILABLE` or
  `OWN_LISTING`, `ADDRESS_REQUIRED`, `DELIVERY_NOT_SUPPORTED`, `AMOUNT_TOO_LARGE`) gets
  `422 CHECKOUT_BLOCKED` with the fresh preview in `details`. Another Customer's or a deleted address gets
  `404 ADDRESS_NOT_FOUND`. The self-purchase guard (F02) is repeated here through `OWN_LISTING`.
- **Zero-total Order (Q01, option a):** an Order whose total is 0 VND is rejected with `422 ORDER_TOTAL_ZERO`; no
  Order or Payment is created. Digital prices are above 0, so this arises only for physical items costing 0 with
  no shipping fee configured.
- **Duplicate submission (Q18, option a):** the Cart version is the commit token. Order creation updates the Cart
  row under the confirmed version, which blocks concurrent edits and a concurrent submission of the same Cart until
  it ends, and empties the Cart. A second submission therefore gets `409 CART_CONFLICT`; a client that lost the
  response finds its pending Order with `GET /orders`. No schema change.
- **Payment window (R1):** `app.orders.payment-window=PT30M`; `ExpiresAt = CreatedAt + window`. Validated at
  startup (positive), like the other configured values.
- **Expiry (R2):** a job runs every `app.orders.expiry-interval` (`PT1M`). In one transaction it sets
  `PENDING_PAYMENT` Orders with `ExpiresAt <= now` to `EXPIRED` (a conditional update) and their `PENDING`
  Payment to `CANCELLED`. Whether an Order can still be paid is decided by `ExpiresAt`, not by when the job runs,
  so F11 must check `ExpiresAt` itself.
- **Payment target (R3):** one `Payment` row (`PENDING`, Amount = Order total, VND) is created with the Order.
  `UX_Payment_Order` allows only one. Attempts, provider events and success belong to F11.
- **Locking (R4):** inside the Order transaction, `UPDLOCK, HOLDLOCK` reads lock the Listing rows, then their
  Artwork, then their Artists, each in ascending ID order. A concurrent price, cost-review flag or status change
  waits until the Order commits or rolls back, and an Order waits for a change in progress and then sees it.
- **Cart after the Order (R5):** the Cart's items are removed in the same transaction. Every line is ordered,
  because a line with a problem blocks checkout.
- **Billing (R6):** no separate billing input. `BillingName` and `CustomerNameSnapshot` are the Customer's display
  name at checkout, `CustomerEmailSnapshot` their email, `BillingDetails` null.
- **Item snapshots (§10, amendment B.5):** Listing, Artwork and Artist identity and names, specifications, quantity,
  unit price, per-line tax (half-up per line, Order tax = sum of item taxes, as in F09), fee rate `0` and fee amount
  `0`. Artist proceeds are the full fee base: the price for a digital item, quantity × markup for a physical one,
  whose per-unit manufacturing cost and markup are recorded. Every Listing file is recorded in `OrderItemAsset`
  (§10.19), including a physical item's files, granted later (F12).
- **Shipping snapshot:** for Orders with physical items, `AddressService.selectForCheckout` copies the address
  into `OrderShippingAddress` with the province name current at checkout.
- **Read APIs (R7):** a Customer reads only their own Orders; another Customer's Order is `404 ORDER_NOT_FOUND`.
  Responses include each item's Listing asset IDs (the `assetId` used by purchased-file downloads, F08). The
  manufacturing cost, markup, fee and Artist proceeds are recorded but not returned to the buyer.
- **Computed columns (R8):** `MarketplaceOrder.TotalAmount` and `OrderItem.LineSubtotal` are computed by SQL
  Server. Their entity mappings are read-only and read back after insert; `tooling/NormalizeEntities.java` now
  applies this to every computed column, so regeneration keeps it. No schema change.
- **License contract (R9):** not part of F10. Its fields are undecided (amendment §E) and it needs a schema
  decision; revisit before F11, because the Customer agrees before paying.
- **Who:** only a Customer bearer token (Artists are Customers). Staff tokens get `403`.

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `POST /orders` | Customer | `{cartVersion, addressId?, lines: [{listingId, quantity, unitPrice}], totalAmount}`; `201` with the Order |
| `GET /orders` | Customer | the caller's Orders, newest first |
| `GET /orders/{orderId}` | Customer | the caller's Order with items, asset IDs, shipping snapshot and payment target |

New error codes: `ORDER_NOT_FOUND` (404); `CHECKOUT_CHANGED` (409); `CHECKOUT_BLOCKED`, `ORDER_TOTAL_ZERO`
(422). `CHECKOUT_CHANGED` and `CHECKOUT_BLOCKED` carry the fresh checkout preview in the problem's `details`.

## Hand-off to F11

- Pay only Orders that are `PENDING_PAYMENT` with `ExpiresAt` in the future, and update the Order conditionally on
  that status so a late callback and the expiry job cannot both win.
- The `Payment` row exists from Order creation with the exact amount; a success for an Order the job has already
  expired (`Payment.Status = CANCELLED`) is a reconciliation exception (§12.12).
- Order status values used so far: `PENDING_PAYMENT`, `EXPIRED`. `CANCELLED` is unused.

## Known limits

- The province's support is read, not locked: an Admin withdrawing support during an Order transaction does not
  wait for it.
- Integration tests never commit an Order (the snapshot tables are immutable); the duplicate-submission test shows
  that a concurrent submission waits for the first and that a resubmission after it is rejected, not a committed
  pair.
