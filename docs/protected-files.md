# Protected files (F08)

Decisions recorded 31 October 2026 for the F08 increment. Entitlements are granted by F12 and Orders are created by
F10; until then, no rows reach the purchased-file path outside tests.

## Approved policies

- **Purchased access (R1):** a Customer may download a protected file only through their own `DigitalEntitlement`
  whose `EntitlementAsset` names that Listing asset; the file served is the one recorded in `OrderItemAsset` when
  the Order was created (§5.3, §27.5). The file must have `AccessClass = PURCHASED`; any other class is denied and
  logged. A physical buyer has no entitlement until F12 grants it when the 48-hour return window closes
  (amendment §C.10), so access is denied before then.
- **Status changes do not revoke access (R2, B2):** unlisting or moderating the Listing or Artwork, deactivating the
  Artist, or removing the asset link from a draft does not affect granted access (§2.7, §5.4, §5.6, §28). Moderation
  stops new sales only.
- **Responses (R3):** no entitlement to an existing asset → `403 ENTITLEMENT_REQUIRED`; unknown asset →
  `404 LISTING_ASSET_NOT_FOUND`.
- **Missing object (R4):** metadata without bytes → `404 FILE_CONTENT_MISSING`, logged as an error with the file ID.
- **Streaming (R5):** every file download (previews, Listing files, purchased files) is streamed from storage with
  its `Content-Length` instead of being read into memory. Purchased files are sent as an attachment with the
  original file name.
- **No direct object URLs:** bytes are served only through authenticated endpoints that check access on each
  request; storage keys are random and never returned to buyers.
- **Orphan cleanup (B1):** a scheduled sweep (`app.storage.orphan-sweep-interval`, default every 6 hours, first run
  one interval after start) deletes files under `public/` and `protected/` that have no `StoredFile` row and were
  last modified more than `app.storage.orphan-min-age` (default 24 hours) ago. The age guard keeps it away from
  uploads whose transaction has not committed yet. `StoredFile` rows are never deleted (`TR_File_Storage_Immutable`
  rejects it), so files with a row are always kept, referenced or not.
- **Commission files** (participant or Admin access) are priority 3.

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `GET /purchases/assets/{assetId}/content` | Customer | file bytes, `Content-Disposition: attachment` |

`assetId` is the Listing asset ID recorded on the Order Item. New error code: `ENTITLEMENT_REQUIRED` (403).

## Known limits

- Listing the Customer's entitlements (a "library") is F12; until then a client needs the asset ID from the Order.
- The sweep runs on every instance; it is safe to run concurrently, because it deletes only bytes without a row.
- Unreferenced `StoredFile` rows (for example a removed draft preview) keep their bytes permanently.
- Historical access after account deletion (Q16) is deferred with account deletion.
