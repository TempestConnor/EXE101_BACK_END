# Implementation scope and priorities

Decision recorded: 27 September 2026.

F01 increment: Customer registration and bearer-token login use the approved
password, verification, and blocking policies recorded in
[Customer authentication](customer-authentication.md). Account deletion remains
deferred.

F02 increment: staff login, Admin bootstrap and audited Admin-only Artist
authorization follow the decisions recorded in
[Staff authentication and Artist authorization](staff-artist-authorization.md).
The self-purchase guard is enforced with Cart and Order creation (F09/F10).

F04 increment: Customer address book, Admin-maintained supported provinces and the checkout address check follow
[Customer addresses and supported delivery areas](address-delivery-areas.md). Writing the Order shipping-address
snapshot and the shipping charge (Q08) belong to F10.

F05 increment: Artwork drafts, publication, Admin moderation, controlled tags and public preview images follow
[Artwork catalog, preview media and tags](artwork-catalog.md). Purchase availability waits for Listings (F06);
protected source and purchased files remain F08.

F06 increment: digital and physical Listings, demo manufacturing costs, protected Listing files and purchase
availability follow [Digital and physical Listings](listing-catalog.md). The checkout quantity rule belongs to
F09/F10, cost-change review to F07, and buyer file access to F08/F12.

F07 increment: manufacturing-cost changes flag affected physical Listings for Artist review, compared once per
start against the configured costs, follow [Manufacturing cost review](manufacturing-cost-review.md). No Platform
Fee configuration; the zero-fee Order Item snapshot is written by F10.

F08 increment: entitlement-gated purchased-file downloads, streamed file responses and the scheduled orphan-bytes
sweep follow [Protected files](protected-files.md). Granting entitlements belongs to F12; Commission file access to
priority 3.

F09 increment: the Cart with live prices, invalid items kept and marked, and the checkout preview with a
flat shipping fee and a configured per-line tax rate follow [Cart and checkout](cart-checkout.md). Order creation,
its atomic re-check and the price-difference rejection belong to F10.

F10 increment: Order creation in one locked transaction, immutable item, file, shipping and Customer snapshots,
a pending payment target, the 30-minute payment window and the expiry job follow
[Order creation and immutable snapshots](order-creation.md). Zero-total Orders are rejected (Q01) and the Cart
version is the checkout commit token (Q18). Payment attempts and confirmation belong to F11; the license contract
waits for its fields and a schema decision.

F11 increment: VNPay payment attempts and signed IPN notifications, deduplicated and locked so an Order is paid
once, with late, wrong-amount, unknown and second successes recorded as exceptions, follow
[Provider payment](payment.md). Quote payments belong to priority 3, entitlement grants to F12, releases to F14,
refunds of exception payments to F15; the license contract stays out until its fields and schema are decided.

F12 increment: direct digital items are granted their recorded files once, inside the payment-success transaction,
and buyers list them with `GET /purchases`, following [Digital entitlements](digital-entitlements.md). The physical
grant at the close of the return window is added by F13/F15 through the same grant operation.

F13 increment: Admin-only, forward-only fulfillment of each physical item from payment to delivery, and the
included-file grant when the 48-hour return window closes with no open or approved return, follow
[Physical fulfillment](physical-fulfillment.md). `FAILED`/`CANCELLED` and return requests belong to F15, the
release of physical proceeds to F14.

F14 increment: one `HELD` proceeds record per paid Order Item from its snapshot, released on payment (digital) or at
the return-window close (physical) as one Admin-recorded release per item, with 0 VND proceeds settled without a
release, follow [Artist proceeds and releases](artist-proceeds.md). Every money event is also written to an
append-only double-entry ledger, and a reconciliation mismatch blocks releases; the money rules and the class
release's declared limitations are amendment F of the business rules. `VOID` on refund belongs to F15, Quote
proceeds to priority 3.

F15 increment: whole-item refunds of physical items before delivery, and returns requested within 48 hours after it,
decided by the Admin under the same locks as the window close, refunding the line total and its tax, with the Order's
shipping refunded once at the approval that completes the physical portion, follow [Refunds](refunds.md). The Admin
sends each refund by hand and records its outcome; every refund is posted to the ledger (migration `0003`). Refunds of
F11 exception payments stay manual and unrecorded; Commission refunds belong to priority 3.

F03 increment (priority 2): Admin-only, audited deactivation and reinstatement of Artists, stored in the existing
`SUSPENDED` status and `ArtistSuspension` rows, with purchase availability computed from the status and held funds
unchanged, follow [Artist deactivation](artist-deactivation.md). Pending Commission requests and Quotes (Q05) belong
to priority 3.

F16 increment (priority 3): Commission requests to an `ACTIVE` Artist who accepts them, with the Customer's
public-display choice (migration `0004`), protected reference files, a participant-only thread that cannot be
edited, and the Artist's commission settings, follow [Commission requests](commission-requests.md). Quotes, the
sketch stage, payments and the license contract belong to F17 onwards.

F17 increment (priority 3): the commissioned Artist offers one Quote at a time (initial price whole VND above 0, tax at
the checkout rate fixed at the offer), the Customer accepts or declines it, the Artist withdraws it or declines the
request, and acceptance writes the zero-fee snapshot and awaits the first 50% payment, following [Commission
Quotes](commission-quotes.md). The first payment now comes before the sketch (amendment §D.12, changed 2 November
2026). Quote payments, change Quotes and the license contract belong to F17b.

F17b increment (priority 3): the first 50% payment of an accepted Quote, through VNPay like an Order, moves the
Commission to `IN_PROGRESS` and holds the Artist's share. Each Quote is paid in two installments (migration `0005`), with
the first half of the price and of the tax rounded up, following [Commission Quote payments](commission-quote-payments.md).
The final payment waits for the Customer's approval (F18). Change Quotes, releasing Quote proceeds, the 7-day close and
the license contract come later.

F18 increment (priority 3): the commissioned Artist submits immutable versions (migration `0006`), and the Customer
approves the latest one or asks for a revision, following [Commission delivery](commission-delivery.md). Approval
returns the Commission to `AWAITING_PAYMENT` for the final 50%; the Customer cannot download the files until it is
paid. Work stages are not recorded (rule change of 2 November 2026, amendment §D.13 and §D.15). The final payment,
completion, Quote releases and the 7-day close belong to F18b; the review preview waits for its format.

Requirements change recorded 31 October 2026: the site is the storefront of one
Artist team, with no Platform Fee, held funds released to each Artist's own
account, a 48-hour post-delivery return window, split Commission payments, and
deactivation instead of suspension/appeals.
See the [business rules amendment](Marketplace_Business_Rules_Final.md#amendment--single-artist-team-31-october-2026).
Its effects on the priorities below are noted in place.

The initial release is a controlled class/demo project. The agreed priority is to
complete standard orders first, implement basic Artist suspension second, and
consider Commissions third. This is an implementation sequence, not a removal of
those capabilities from the long-term product.

## Authority and agent behavior

Read this document before planning or implementing features. It controls release
scope and implementation order where the [feature specification](EXE101_Features_Specification.md)
or [business rules](Marketplace_Business_Rules_Final.md) describe broader scope.
In particular, it overrides the specification's suggested implementation order
for the capabilities listed here. The source documents still define behavior for
features being implemented; this document does not settle their open questions.

- Focus on the current priority before expanding into the next one, unless the
  user explicitly requests otherwise. This document is not an instruction to
  implement all priorities in a single task.
- Do not implement deferred workflows or speculative supporting endpoints,
  services, dependencies, or scaffolding merely because the specification calls
  them required or core.
- Preserve existing entities and database structures. Deferral does not authorize
  deleting mappings, tables, data, or historical records.
- Preserve shared requirements needed by active features, including authorization,
  availability checks, immutable snapshots, audit, and transaction integrity.
- If an active feature needs additional deferred functionality, explain the
  concrete dependency and obtain a scope decision before expanding into it.
  The shared requirements explicitly retained below are already in scope.
- Explicit user instructions may change these priorities or activate a deferred
  feature. A recommendation alone does not change the agreed scope.

## Priority 1: Complete the standard Order workflow

Implement the standard-order portions of F01, F02, F04–F15 and F21, plus the
Artist availability checks from F03. Completion means an end-to-end workflow:

1. Customers authenticate and Admin authorizes Artists.
2. Artists publish digital and physical offerings with appropriate pricing and
   protected assets.
3. Customers check out and pay for Orders with immutable snapshots.
4. Digital purchases grant the purchased files; physical items progress through
   fulfillment and grant their included files on delivery.
5. Artist proceeds and payouts are tracked, and eligible physical-item and
   shipping refunds can be handled under the specified rules.

Under the 31 October amendment:

- F07 retains manufacturing-cost review but not Platform Fee configuration. Order
  Item and Quote snapshots record a `0` fee rate and `0` fee amount, and Artist
  proceeds equal the full fee base.
- F14 becomes release of held proceeds to each Artist's own account. Digital
  proceeds are released on payment. Physical proceeds are released when the
  48-hour return window closes with no open or approved return.
- F12 grants a physical item's included files when that window closes, not on
  delivery.
- F15 adds post-delivery return requests within 48 hours.
- F01 needs recorded acceptance of the customer Policy at registration; not yet
  implemented. It depends on a final, versioned Policy text.
- The license contract for digital purchases is a new Policy requirement. Its
  placement in this priority needs a scope decision before implementation.

These changes may need schema additions (consent, contracts, return window).
The database remains the schema authority, so each needs a separate schema
decision.

Checkout alone is not completion. Payment deduplication, file authorization,
historical retention, financial correctness, and required Admin audit remain
part of this priority. Commission-specific behavior in shared payment, file,
and proceeds features belongs to priority 3.

Artist status must already be enforced when publishing Listings and atomically
validating availability at Order creation. Do not assume every Artist is ACTIVE
because the suspension management workflow is not yet implemented. Existing
unexpired Orders remain payable under their original terms after suspension;
previous purchases and entitlements remain preserved.

Resolve the specification's applicable open questions before implementing the
affected paths, including payment anomalies, shipping/tax inputs, rounding,
fulfillment transitions, and refund/payout coordination. Deferral is not a
default answer to these questions.

## Priority 2: Artist deactivation

Under the 31 October amendment, suspension is replaced by member deactivation.
Decided 2 November 2026: the Admin may reinstate a deactivated member, and the
existing `SUSPENDED` status and suspension history structures are the
representation ([Artist deactivation](artist-deactivation.md)).

After the standard Order workflow, implement the reduced F03 workflow:

- Admin-only deactivation (indefinite).
- Reasons, staff identity, timestamps, and retained deactivation history.
- Transactional audit of status changes.
- New-purchase restrictions enforced through Artist availability rather than
  rewriting every Listing.
- Preservation of the Artist's Customer capabilities and existing commercial
  records and commitments.

This is planned secondary work, subject to available project time. It is not a
prerequisite for completing the standard-order demo milestone, apart from the
availability checks retained in priority 1.

Timed suspension, automatic expiry/extension, and the appeal workflow are removed
from the product by the 31 October amendment, not merely deferred.

## Priority 3: Commissions

F16–F20 and the Commission-specific extensions to F03, F08, F11, F14 and F21 are
tertiary work. The 31 October amendment changes this journey substantially:
sketch stage, two 50% payments per Quote, tracked work stages, progress-based
refunds, preview-only review, a 7-day final-payment deadline, a portfolio
opt-in, and license contracts. Its lifecycle needs schema review before
implementation. Start this journey after standard orders and basic suspension,
unless the user explicitly changes priorities. Commissions are not required to
complete the standard-order demo milestone.

Treat requests, messaging, Quotes, payment, delivery/revisions, cancellation,
disputes, and refunds as a connected journey when planning this phase. Resolve
its outstanding questions, including Q05 on suspension of pending requests and
Quotes, before implementing the affected behavior. Preserve the specified right
to continue already IN_PROGRESS work during suspension.

## Deferred workflows

### Admin auditing scope (F21)

Implement minimal automatic, persistent audit records together with in-scope
sensitive Admin actions. Record the responsible staff member, action, target,
timestamp, and reason/context where applicable. Commit the audit record in the
same transaction as the action so both succeed or both fail. Application logs
are not a substitute for these records.

Audit support follows the feature being implemented: standard-order refund and
moderation decisions in priority 1, suspension/reinstatement in priority 2, and
Commission decisions in priority 3. Appeal auditing waits until appeals are
activated. Do not build audit workflows for otherwise deferred features.

Defer dedicated audit browsing dashboards, search/filter interfaces, reporting,
exports, and routine manual review tooling. The Admin does not manually create
an audit record; the system captures it when the Admin performs the business
action, including any required reason. This deferral does not remove historical
retention or the requirement that recorded evidence remain inspectable.

The specification does not explicitly require a dedicated audit-management
interface. This boundary prevents adding one as speculative scope; it does not
defer the automatic recording required by F21 and business rules section 29.

| Capability | Decision and rationale | Revisit point |
| --- | --- | --- |
| Customer account deletion (part of F01) | Defer the user-facing deletion workflow. Standard orders do not require it; email release, anonymization, authentication/session invalidation, and historical access need deliberate lifecycle decisions. | After standard orders, or when preparing for use beyond the controlled demo. |
| Artist appeals (part of F03) | Removed by the 31 October amendment. Keep the existing `ArtistAppeal` mapping and table unused. | Only if the product reintroduces appeals. |
| Timed suspension (part of F03) | Removed by the 31 October amendment; deactivation is indefinite. | Only if the product reintroduces timed suspension. |
| Platform Fee configuration (part of F07) | Removed by the 31 October amendment; snapshots record a zero fee. Keep the existing `PlatformFeeRate` mapping and table unused. | If the team reintroduces a fee. |
| Dedicated Admin audit tools | Defer audit browsing dashboards, search/filter interfaces, reporting, exports, and routine manual review tooling. Keep automatic persistent audit recording with each implemented sensitive action. | After standard orders, when an operational audit-review need is identified. |

Deferring account deletion does not defer stable User IDs, retained transaction
links, order snapshots, or purchased assets. Keep existing account lifecycle
fields and do not use destructive database deletion as a substitute. Q16 remains
unresolved, including access to historical entitlements after deletion. The
deletion workflow must be defined before production, as required by F01.

No other feature is automatically deferred by this document.
