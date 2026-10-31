# Cart and checkout quotation (F09)

Decisions recorded 31 October 2026 for the F09 increment.

## Approved policies

- **Live prices:** the Cart stores only Listing and quantity. Every Cart view and checkout preview reads the
  Listing's current price; nothing is reserved or locked (§8.4–8). Manufacturing cost and markup are not shown.
- **Price-change confirmation (B1, option a):** the preview returns the Cart version, each line's current unit
  price and the amounts. Order creation (F10) requires the client to send these back and rejects any difference
  with `409` and a fresh preview, so a change is measured against the last preview the Customer saw. No schema
  change.
- **Shipping (Q08):** one flat configured fee per Order with at least one physical item; 0 for digital-only
  Orders. A quote is always available. Demo value `app.checkout.shipping-fee=30000` VND.
- **Tax (Q09):** one configured rate applied to each line total (unit price × quantity), rounded **half-up** to
  whole VND per line. Order tax = sum of the line taxes. Demo value `app.checkout.tax-rate=0` (a fraction:
  `0.10` = 10%). The real rate is still to be supplied; changing it affects only later previews and Orders.
- **Who:** only a Customer bearer token (Artists are Customers). Staff tokens get `403`.
- **Adding:** the Listing must exist (`404 LISTING_NOT_FOUND`), must not be the Customer's own
  (`422 SELF_PURCHASE_NOT_ALLOWED`) and must be purchasable as defined in [Listings](listing-catalog.md)
  (`422 LISTING_NOT_PURCHASABLE`). The Cart row is created on the first add.
- **Quantity:** a positive integer; exactly 1 for a digital Listing; at most `app.checkout.max-physical-quantity`
  (99) per physical line (`400 CART_QUANTITY_INVALID`). The limit is a sanity limit; it does not prevent
  amounts too large to store (see below).
- **Adding again:** a physical Listing adds to the quantity already in the Cart (the result must stay within the
  limit); a digital one stays at 1.
- **Invalid items (R1):** kept in the Cart, never removed automatically. The Cart view and the preview mark each
  with a problem (`OWN_LISTING`, `LISTING_UNAVAILABLE`, `QUANTITY_NOT_ALLOWED`), and checkout stays blocked until
  the Customer removes them, because an unlisted Listing can be published again.
- **Preview:** always `200` with the lines, items subtotal, shipping, tax, total, a list of problems and
  `committable`. Only malformed input gets `400`; another Customer's or a deleted address gets
  `404 ADDRESS_NOT_FOUND`. Lines with a problem are shown but not counted in the amounts, so the amounts are what
  the Customer could commit to. Blocking problems: `CART_EMPTY`; a line problem; `ADDRESS_REQUIRED` (physical
  items without `addressId`); `DELIVERY_NOT_SUPPORTED` (the address's province has lost support);
  `AMOUNT_TOO_LARGE`.
- **Storable amounts:** `OrderItem.LineSubtotal`/`TaxAmount` and `MarketplaceOrder.ItemsSubtotal`/`TaxAmount`
  are `decimal(19,4)` (below 10^15) and `TotalAmount` is `decimal(21,4)` (below 10^17). The preview computes exact
  amounts and reports `AMOUNT_TOO_LARGE` when a counted line or its tax, the items subtotal or the tax reaches
  10^15, or the total reaches 10^17.
- **Concurrent edits (R5):** every item change updates the `Cart` row under its `RowVer`. An edit whose read
  version is no longer current, or a first add that loses the race to create the Cart (`UQ` on `UserId`), gets
  `409 CART_CONFLICT`; no update is lost. Edits do not send a version.
- **Not in the Cart:** updating or removing a Listing that is not in the Cart gets `404 CART_ITEM_NOT_FOUND`.
- **Out of scope here:** Order creation, snapshots and the final atomic availability and price check (F10);
  checkout idempotency (Q18, F10); zero-total Orders with physical items (Q01, F10); payment (F11). F10 decisions:
  [Order creation](order-creation.md).

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `GET /cart` | Customer | Cart with current prices and line problems; empty when none exists |
| `POST /cart/items` | Customer | `{listingId, quantity}`; the updated Cart |
| `PUT /cart/items/{listingId}` | Customer | `{quantity}`; the updated Cart |
| `DELETE /cart/items/{listingId}` | Customer | `204` |
| `GET /cart/checkout-preview?addressId=` | Customer | preview; `addressId` needed for physical items |

New error codes: `CART_QUANTITY_INVALID` (400); `CART_ITEM_NOT_FOUND` (404); `CART_CONFLICT` (409);
`LISTING_NOT_PURCHASABLE`, `SELF_PURCHASE_NOT_ALLOWED` (422).

Integer JSON fields across the API now reject fractional numbers (`1.5`) with `400 INVALID_REQUEST` instead of
truncating them (`spring.jackson.deserialization.accept-float-as-int=false`).

## Hand-off to F10

- `ListingService.findOffers(listingIds)` returns each Listing's live type, name, price, purchasable flag and
  owner. F10 must repeat the check under its own locking in the Order transaction.
- `CheckoutPreview` carries the values F10 compares: Cart version, per-line unit prices and quantities, and the
  amounts.
- `AddressService.get` and `ProvinceService.isSupported` check the address without throwing for withdrawn
  support; F10 still uses `selectForCheckout` for the snapshot.

## Known limits

- The preview reads each Listing's Artwork and Artist one by one; Carts are small.
- Configured tax, shipping and quantity values are validated at startup; invalid values stop the application.
