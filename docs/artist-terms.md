# Artist terms (F20)

Decisions recorded 2 November 2026, as the follow-up deferred by [Commission refunds](commission-refunds.md). A refund
after a paid Commission is cancelled is a percentage under "the terms the Artist publishes and the Customer agreed to
before the work started" (amendment §D.15). This stores those terms (text and images) and records on each Quote which
version the Customer accepted, so the Customer, the Artist and an Admin resolving a dispute read the same text later.

## Approved policies

- **Immutable versions with a Quote link (blocking decision):** each publication adds a version (`ArtistTerms`, with
  its images in `ArtistTermsImage`) and never rewrites an earlier one. `TR_ArtistTerms_Immutable` and
  `TR_ArtistTermsImage_Immutable` refuse any update or delete. Accepting a Quote records the version in
  `CommissionQuote.ArtistTermsId`, which `TR_Quote_Terms_Immutable` now also protects after acceptance
  (migration `0008_artist_terms.sql`). Quotes accepted before this have `NULL`.
- **Terms are required before a Quote (blocking decision):** an Artist without published terms cannot offer a Quote
  (`409 ARTIST_TERMS_REQUIRED`). A Quote offered before terms existed cannot be accepted until the Artist publishes
  them; the same code applies.
- **The Customer accepts the version they read:** the accept request carries `artistTermsId`. If it is not the
  Artist's current version, the acceptance is refused with `409 ARTIST_TERMS_OUTDATED` and the Quote stays `OFFERED`.
- **Text:** 1–20 000 characters after trimming (`400 ARTIST_TERMS_INVALID`). VGen-style commission terms are longer
  than the 5000-character message limit.
- **Images:** JPEG, PNG or WebP, detected from the file signature; at most 10 MB each and 10 per version, stored as
  `PUBLIC` files, the same rules as Artwork previews (F05). Images of the current version can be carried into the new
  one by their ID (`keepImageIds`, kept first, in the order given) without uploading them again; any other ID is
  `400 ARTIST_TERMS_INVALID`. Error codes: `415 ARTIST_TERMS_IMAGE_TYPE_NOT_SUPPORTED`,
  `413 ARTIST_TERMS_IMAGE_TOO_LARGE`, `409 ARTIST_TERMS_IMAGE_LIMIT_REACHED`. Every check runs before a file is stored.
- **Who publishes:** only an `ACTIVE` Artist (`409 ARTIST_NOT_ACTIVE`); a user without an Artist Profile gets
  `403 ARTIST_PROFILE_REQUIRED`, as for commission settings (F16). A deactivated Artist's terms stay readable.
- **Public read:** the current terms and the image content of every version need no token, like the public profile.
- **No deletion:** terms are only replaced by publishing again, because a refund or a dispute may rely on them.

## Concurrency

- Publishing locks the Artist's row (`UPDLOCK`), so one Artist's publications run one at a time and wait for a
  deactivation in progress. `UQ_ArtistTerms_Version` (`ArtistId`, `VersionNo`) backs this up.
- Accepting a Quote locks the Commission, reads the Artist under the shared lock of a request, then reads the latest
  terms version with `HOLDLOCK`. The key range after it stays locked until the acceptance commits, so a publication
  either commits first (and the acceptance is refused as outdated) or waits until after it. The Customer can never
  bind a version that was no longer current.

## HTTP contract

| Endpoint | Who | Request | Success |
| --- | --- | --- | --- |
| `POST /artists/me/terms` | the Artist (Customer token) | multipart: `request` JSON `text`, optional `keepImageIds`; up to ten `images` parts | 201 with the terms |
| `GET /artists/{artistId}/terms` | anyone | — | 200, the current terms (`404 ARTIST_TERMS_NOT_FOUND` if none) |
| `GET /artists/{artistId}/terms/images/{imageId}/content` | anyone | — | the image bytes (`404 ARTIST_TERMS_IMAGE_NOT_FOUND`) |
| `POST /commissions/{id}/quotes/{quoteId}/accept` | the Customer | JSON `artistTermsId` | 200 with the Quote ([Commission Quotes](commission-quotes.md)) |
| `GET /commissions/{id}/quotes/{quoteId}/terms` | participants | — | 200, the version the Quote was accepted under |
| `GET /admin/commissions/{id}/quotes/{quoteId}/terms` | Admin | — | 200, the same |

Terms carry `artistTermsId`, `artistId`, `versionNo`, `text`, `publishedAt` and `images` (`imageId`, `sortOrder`,
`contentType`, `byteLength`). A Quote not yet accepted, or accepted before terms existed, has no terms
(`404 ARTIST_TERMS_NOT_FOUND`); a non-participant gets `404 COMMISSION_NOT_FOUND`.

## Financial correctness

No money moves here. The terms are evidence for the refund percentage of [Commission refunds](commission-refunds.md):
the version a Quote was accepted under cannot be changed or deleted in the database, and the Quote's link to it is
part of the immutable accepted terms.

## Known limits

- **The terms are free text,** so nothing checks a refund percentage against them; the Customer's acceptance and the
  Admin's judgement in a dispute are the checks. Closing it: a structured refund schedule per Quote.
- **A failed publication leaves its image bytes on disk** until the orphan sweep (F08) removes them, as for
  previews.
- **The race between a publication and an acceptance has no database test;** it relies on the `HOLDLOCK` range lock
  described above.
- The [Commission refunds](commission-refunds.md#known-limits) limits are unchanged.

## Tests

- **Unit:** `ArtistTermsServiceTest`; the terms cases in `CommissionQuoteServiceTest`; `ArtistTermsControllerTest`
  and the accept request in `CommissionQuoteControllerTest`.
- **Persistence:** `ArtistTermsPersistenceTest` runs only against the test database selected by `TEST_DB_URL` and
  leaves its rows there, because published terms cannot be deleted. The Commission persistence tests publish terms
  for each new Artist before offering a Quote.
