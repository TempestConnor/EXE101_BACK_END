# Digital and physical Listings (F06)

Decisions recorded 31 October 2026 for the F06 increment.

## Approved policies

- **Ownership:** a Listing belongs to one Artwork; its owner is the Artwork's Artist. Another Artist's Listing
  returns `403 ACCESS_DENIED`.
- **Inactive Artists** (profile not `ACTIVE`) cannot create, edit or publish Listings, or add or remove files.
  They can still view, unlist and archive their own Listings.
- **Final Artwork:** no Listing can be created or edited under `MODERATED` or `ARCHIVED` Artwork. A draft
  Listing may be prepared while its Artwork is still `DRAFT` or `UNLISTED`.
- **Status changes** follow Artwork:

  | From | To | Who |
  | --- | --- | --- |
  | `DRAFT` | `PUBLISHED`, `ARCHIVED` | owner or Admin |
  | `PUBLISHED` | `UNLISTED`, `ARCHIVED` | owner or Admin |
  | `UNLISTED` | `PUBLISHED`, `ARCHIVED` | owner or Admin |
  | `PUBLISHED`, `UNLISTED` | `MODERATED` | Admin only, reason required |

  Publishing needs a `PUBLISHED` Artwork, an `ACTIVE` Artist (also when the Admin publishes), at least one
  file, and every file's bytes present in storage. `MODERATED` and `ARCHIVED` are final. `PublishedAt` records
  the first publication.
- **Audit:** every Admin status change writes an `AdminAudit` record in the same transaction: target `Listing`,
  action `LISTING_MODERATED` for moderation and `LISTING_STATUS_CHANGED` otherwise, details `from=…;to=…`.
- **Purchasable** (computed, never stored): Listing `PUBLISHED`, Artwork `PUBLISHED`, Artist `ACTIVE`, and
  `CostReviewRequired = 0`. Artwork is purchasable when at least one of its Listings is. An unlisted Artwork
  hides its Listings from the catalog.
- **Catalog:** shows the `PUBLISHED` Listings of `PUBLISHED` Artwork. A Listing of an inactive Artist, or one
  waiting for cost review, is shown with `purchasable: false`. Manufacturing cost and markup are not public.
- **Money:** VND only, whole đồng (no fractions), stored as `decimal(19,4)`, at most 15 integer digits, no
  further cap. A digital price must be above 0, which avoids zero-price Orders while Q01 is open. A physical
  markup may be 0.
- **Physical pricing:** the Artist picks a manufacturing product and a markup. The server copies the product's
  cost and specifications and sets retail = cost + markup; a client-sent physical price is rejected. Products
  and costs are **demo placeholders** in `app.manufacturing.products` (application properties) until the real
  costs are known. Changing them affects only new Listings; reacting to a cost change is F07.
- **Editing after first publication:** name, description, digital price and physical markup (retail is
  recomputed) may change, as current prices may (§30.2). Type, product, specifications and files are locked
  (§6.6; files also by `TR_ListingAsset_Published`). Every edit sends the `version` from the last read; a stale
  version returns `409 LISTING_VERSION_CONFLICT`.
- **Files:** stored as `StoredFile` with `AccessClass = PURCHASED` under `app.storage.root/protected/`. Both
  digital and physical Listings need at least one file to publish (§5.1, §6.2). Accepted by extension: png, jpg,
  jpeg, webp, tif, tiff, psd, pdf, zip, glb, gltf, obj, fbx, stl, blend; contents are not inspected. At most
  200 MB each and 10 per Listing. Removing a file deletes only the `ListingAsset` link. Only the owner and the
  Admin can download Listing files; buyers download through their entitlement ([Protected files](protected-files.md)).
- **Out of scope here:** the quantity rule (digital = 1) at checkout (F09/F10); cost-change review (F07, see
  [Manufacturing cost review](manufacturing-cost-review.md));
  buyer file access (F08/F12); Order snapshots (F10).

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `GET /listings/products` | Customer (Artist) | demo manufacturing products and costs |
| `GET /listings?artworkId=` | Customer (Artist) | own Listings of one Artwork, any status |
| `GET /listings/{listingId}` | Customer (Artist) | one owned Listing |
| `POST /listings` | Customer (Artist) | `201` Draft |
| `PUT /listings/{listingId}` | Customer (Artist) | name, description, price or markup, product; requires `version` |
| `PUT /listings/{listingId}/status` | Customer (Artist) | owner status change |
| `POST /listings/{listingId}/assets` | Customer (Artist) | multipart `file`; `201` |
| `DELETE /listings/{listingId}/assets/{assetId}` | Customer (Artist) | `204`, removes the link |
| `GET /listings/{listingId}/assets/{assetId}/content` | Customer (Artist) | file bytes |
| `GET /admin/listings?artworkId=`, `GET /admin/listings/{listingId}` | Admin | any Listing |
| `PUT /admin/listings/{listingId}/status` | Admin | status change or moderation, audited |
| `GET /admin/listings/{listingId}/assets/{assetId}/content` | Admin | file bytes |
| `GET /catalog/artworks/{artworkId}/listings` | anyone | published Listings of published Artwork |

New error codes: `LISTING_INVALID` (400); `LISTING_NOT_FOUND`, `LISTING_ASSET_NOT_FOUND` (404);
`LISTING_PRODUCT_UNKNOWN` (422); `LISTING_VERSION_CONFLICT`, `LISTING_NOT_EDITABLE`,
`LISTING_STATUS_CHANGE_NOT_ALLOWED`, `LISTING_ARTWORK_NOT_PUBLISHED`, `LISTING_PRODUCT_LOCKED`,
`LISTING_ASSET_REQUIRED`, `LISTING_ASSET_MISSING`, `LISTING_ASSETS_LOCKED`, `LISTING_ASSET_LIMIT_REACHED`,
`LISTING_ASSET_CONFLICT` (409); `LISTING_ASSET_TYPE_NOT_SUPPORTED` (415); `LISTING_ASSET_TOO_LARGE` (413).
`MODERATION_REASON_REQUIRED` now covers Artwork and Listings.

## Known limits

- Uploads are read fully into memory; a 200 MB file needs that much heap per request. Downloads are streamed (F08).
- Publishing takes a shared lock on the Artist Profile row, so nothing is published after a deactivation commits
  ([Artist deactivation](artist-deactivation.md)). Order creation still re-checks availability in its own
  transaction.
- If a transaction rolls back after the file bytes were written, the bytes stay on disk until the F08 orphan sweep
  removes them ([Protected files](protected-files.md)).
- Lists are not paged.
