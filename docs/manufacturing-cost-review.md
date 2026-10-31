# Manufacturing cost review (F07)

Decisions recorded 31 October 2026 for the F07 increment. Platform Fee configuration is removed by the
[31 October amendment](Marketplace_Business_Rules_Final.md#amendment--single-artist-team-31-october-2026); the
`PlatformFeeRate` table stays unused.

## Approved policies

- **Cost source (B1, option a):** current manufacturing costs stay in `app.manufacturing.products` (application
  properties). A cost changes by editing the properties and restarting. No schema change: a Listing is matched to
  its product by the `Specifications` text it copied at creation, which is locked after first publication.
- **When costs are compared:** once per application start, before the web server accepts requests. One bulk
  `UPDATE` per product sets `CostReviewRequired = 1` on matching physical Listings whose stored
  `ManufacturingCost` differs from the current cost; no Listings are loaded into the application. Requests never
  compare costs; they read the stored flag. An invalid product configuration (negative, fractional or oversized
  cost, blank code or specifications, duplicate specifications) stops the start before any Listing is flagged.
- **R1 — any change flags:** an increase or a decrease.
- **R2 — which Listings:** physical Listings in `DRAFT`, `PUBLISHED` or `UNLISTED`. `MODERATED` and `ARCHIVED` are
  final and are left alone. A flagged Listing cannot be published (also by the Admin), and is not purchasable.
- **R3 — review:** only the owning Artist, while `ACTIVE`, confirms, sending the markup to keep or change and the
  `version` from the last read (stale → `409 LISTING_VERSION_CONFLICT`). The server sets cost = current cost,
  retail = current cost + markup, and clears the flag; the Admin cannot confirm for the Artist. While a Listing is
  flagged, the normal edit (`PUT /listings/{id}`) is rejected, so retail is never recomputed from the old cost.
  The owner's and Admin's Listing views show `currentManufacturingCost` while the flag is set.
- **R4 — no automatic clearing:** the flag is set only where the costs differ, so a restart without a change does
  nothing. It is never cleared automatically, even if the cost returns to the Listing's value; only the Artist's
  confirmation clears it.
- **R5 — audit:** not applicable under option a; there is no Admin cost-update action.
- **R6 — fees:** F07 adds no fee or proceeds code. F10 writes Order Item snapshots with fee rate `0`, fee amount
  `0`, and proceeds = quantity × markup (physical) or the price (digital), as the existing `OrderItem` check
  constraints require.

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `PUT /listings/{listingId}/cost-review` | Customer (Artist) | body `artistMarkup` (whole VND, ≥ 0), `version`; returns the Listing |

New error codes: `LISTING_COST_REVIEW_REQUIRED`, `LISTING_COST_REVIEW_NOT_REQUIRED` (409). A flagged Listing whose
specifications no longer match any configured product cannot be confirmed (`422 LISTING_PRODUCT_UNKNOWN`).

## Known limits

- Editing a product's `specifications` text in the properties breaks the match for Listings that copied the old
  text: they are no longer checked and cannot be confirmed. Change the cost only, or add a new product.
- The check runs per application start, so it relies on a single instance applying the configuration.
- Order creation (F10) must re-check `CostReviewRequired` and the Listing version in its own transaction.
- `DatabaseMappingTest` and other `@SpringBootTest` contexts also run the startup check; it writes only when a
  stored cost differs from the configured one.
