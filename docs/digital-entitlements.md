# Digital entitlements (F12)

Decisions recorded 1 November 2026 for the F12 increment.

## Approved policies

- **Physical items (B1, option a):** F12 grants only direct digital Order Items, on confirmed payment. A physical
  item's included files are granted when its 48-hour return window closes with no open or approved return
  (amendment §C.10); F13 (delivery) and F15 (returns) add that trigger and reuse the same grant operation. Until
  then a physical buyer has no entitlement and downloads are denied (F08 R1).
- **Grant point (R1):** the grant runs inside the payment-success transaction, only when the settlement is `PAID`
  (payment.md R5). Exception payments (wrong amount, late, second success) and failures grant nothing (§12.12). If
  the grant fails, the whole success rolls back, no provider event is recorded and VNPay's retry is handled afresh.
- **Once per item (R2):** under the Order lock taken by the settlement, an item that already has an entitlement is
  skipped (§31.2). `UQ(OrderItemId)` on `DigitalEntitlement` is the database guard.
- **Buyer and assets (R3):** the entitlement's Customer is the Order's buyer, never the caller. Its assets are
  exactly the item's `OrderItemAsset` rows, with no substitution (§5.3, §10.19); `TR_Entitlement_Immutable` keeps
  them unchanged.
- **Missing bytes (R4):** the grant does not check storage. A download of a file whose bytes are missing returns
  `404 FILE_CONTENT_MISSING` (F08 R4), so the payment path does not depend on storage.
- **Library (R5):** `GET /purchases` lists the caller's entitlements, newest first, with each item's snapshot names
  and the granted files. Staff tokens get `403`.

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `GET /purchases` | Customer | `[{entitlementId, grantedAt, orderId, orderItemId, listingId, listingType, artworkTitle, listingName, artistName, files: [{assetId, fileName, contentType, byteLength}]}]` |

Files are downloaded with `GET /purchases/assets/{assetId}/content` (F08).

## Tests

`EntitlementPersistenceTest` commits paid Orders and entitlements, so like `PaymentPersistenceTest` it runs only
against `CuratedArtworkMarketplace_Test`, selected by `TEST_DB_URL` (see [Payment](payment.md#tests)).

## Known limits

- The physical-item grant and the acceptance criterion "when that physical item becomes eligible, its exact files
  become accessible once" wait for F13/F15.
- Historical access after account deletion (Q16) is deferred with account deletion.
- The license contract for digital purchases is not recorded (payment.md B3).
