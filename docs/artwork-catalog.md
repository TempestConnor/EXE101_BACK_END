# Artwork catalog, preview media and tags (F05)

Decisions recorded 31 October 2026 for the F05 increment.

## Approved policies

- **Ownership:** an Artwork belongs to one Artist Profile. The owner is the Customer whose User owns that profile.
  Another Artist's Artwork returns `403 ACCESS_DENIED`.
- **Inactive Artists** (profile not `ACTIVE`) cannot create, edit, publish, or add, move or remove previews. They
  can still view, unlist and archive their own Artwork.
- **Visibility:** only `PUBLISHED` Artwork is visible publicly, to Customers and anonymous visitors. Owners see all
  of their own Artwork in any status; the Admin sees everything.
- **Purchase availability** was always `false` in this increment. Since F06 it is true when the Artwork has a
  purchasable Listing; see [Listings](listing-catalog.md).
- **Status changes:**

  | From | To | Who |
  | --- | --- | --- |
  | `DRAFT` | `PUBLISHED`, `ARCHIVED` | owner or Admin |
  | `PUBLISHED` | `UNLISTED`, `ARCHIVED` | owner or Admin |
  | `UNLISTED` | `PUBLISHED`, `ARCHIVED` | owner or Admin |
  | `PUBLISHED`, `UNLISTED` | `MODERATED` | Admin only, reason required |

  Publishing needs at least one preview and an `ACTIVE` Artist, also when the Admin publishes. `MODERATED` and
  `ARCHIVED` are final: nothing can be edited or changed. Reversing moderation is out of scope. Published Artwork
  never returns to `DRAFT`. `PublishedAt` records the first publication and is kept when Artwork is re-published.
- **Audit:** every Admin status change writes an `AdminAudit` record in the same transaction: target `Artwork`,
  action `ARTWORK_MODERATED` for moderation and `ARTWORK_STATUS_CHANGED` otherwise, details `from=…;to=…`.
  Owner changes are not audited (§29.1).
- **Metadata:** title (≤ 240), description and tags remain editable as metadata corrections. The `2D`/`3D`
  class is locked after first publication, because changing it is a material change (§3.17). A materially
  different work must be published as a new Artwork.
- **Concurrent edits:** the update request carries the `version` (Base64 of `RowVer`) returned by the last read. A
  stale version, or a lost race detected by the row version, returns `409 ARTWORK_VERSION_CONFLICT`.
- **Tags:** the Admin creates, renames, deactivates and reactivates tags. Artists can select only active tags.
  A deactivated tag is hidden from every Artwork view. Its links are kept, so reactivating it restores them.
- **Previews:** JPEG, PNG or WebP, detected from the file signature rather than the declared type. Each preview
  is at most 10 MB, with at most 10 per Artwork. Positions (`SortOrder`) are unique per Artwork and default to
  the next free one. Each upload creates a `StoredFile` with `AccessClass = PUBLIC`; the bytes go under
  `app.storage.root`. Removing a preview deletes only the `ArtworkMedia` link; the file row and bytes are kept.
  Published Artwork must keep at least one preview.
- **Out of scope here:** protected source and purchased files, entitlement checks, and cleanup of unreferenced
  files (F08); Listings and purchasability (F06); Order title snapshots (F10, already protected by
  `OrderItem.ArtworkTitle` and `TR_OrderItem_Immutable`).

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `GET /tags` | Admin | all tags |
| `GET /tags/active` | Customer | active tags, for selection |
| `POST /tags` | Admin | `201` created tag (active) |
| `PUT /tags/{tagId}` | Admin | rename and/or activate/deactivate |
| `GET /artworks` | Customer (Artist) | own Artwork, any status |
| `GET /artworks/{artworkId}` | Customer (Artist) | one owned Artwork |
| `POST /artworks` | Customer (Artist) | `201` Draft |
| `PUT /artworks/{artworkId}` | Customer (Artist) | metadata and tags; requires `version` |
| `PUT /artworks/{artworkId}/status` | Customer (Artist) | owner status change |
| `POST /artworks/{artworkId}/previews` | Customer (Artist) | multipart `file`, optional `sortOrder`; `201` |
| `PUT /artworks/{artworkId}/previews/{mediaId}` | Customer (Artist) | move to `sortOrder` |
| `DELETE /artworks/{artworkId}/previews/{mediaId}` | Customer (Artist) | `204`, removes the link |
| `GET /artworks/{artworkId}/previews/{mediaId}/content` | Customer (Artist) | preview bytes |
| `GET /admin/artworks`, `GET /admin/artworks/{artworkId}` | Admin | any Artwork |
| `PUT /admin/artworks/{artworkId}/status` | Admin | status change or moderation, audited |
| `GET /admin/artworks/{artworkId}/previews/{mediaId}/content` | Admin | preview bytes |
| `GET /catalog/artworks`, `GET /catalog/artworks/{artworkId}` | anyone | published Artwork |
| `GET /catalog/artworks/{artworkId}/previews/{mediaId}/content` | anyone | preview bytes of published Artwork |

Errors follow the Problem Details contract. New codes: `ARTIST_PROFILE_REQUIRED` (403), `ARTIST_NOT_ACTIVE`,
`ARTWORK_VERSION_CONFLICT`, `ARTWORK_NOT_EDITABLE`, `ARTWORK_STATUS_CHANGE_NOT_ALLOWED`,
`ARTWORK_DIMENSION_LOCKED`, `ARTWORK_PREVIEW_REQUIRED`, `ARTWORK_MEDIA_ORDER_TAKEN`, `PREVIEW_LIMIT_REACHED`,
`TAG_ALREADY_EXISTS` (409); `ARTWORK_INVALID`, `MODERATION_REASON_REQUIRED`, `TAG_INVALID` (400);
`ARTWORK_NOT_FOUND`, `ARTWORK_MEDIA_NOT_FOUND`, `TAG_NOT_FOUND`, `FILE_CONTENT_MISSING` (404);
`TAG_NOT_AVAILABLE` (422); `PREVIEW_TYPE_NOT_SUPPORTED` (415); `PREVIEW_TOO_LARGE` (413).

## Known limits

- A change to tags alone does not bump `RowVer`, so two simultaneous tag-only edits are not detected as a conflict.
- If a transaction rolls back after the preview bytes were written, the file stays on disk unreferenced until
  F08 adds cleanup.
- Catalog lists are not paged; there is no list envelope yet.
