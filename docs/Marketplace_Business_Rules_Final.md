# Final Business Rules — Curated 2D/3D Artwork Marketplace (26 September 2026)

This specification consolidates the original requirements and the decisions made during the requirements review. It intentionally describes **business behavior rather than database implementation**. No SQL/schema assumptions are included beyond rules concerning historical preservation and integrity.

## 1. Users, Customers and Staff

1. A person registers using an email address and password.
2. Every registered marketplace account represents exactly one User.
3. Every registered User may act as a Customer.
4. A Customer does not need to be an Artist.
5. An Artist is a User who has been granted an Artist Profile by the Admin.
6. An Artist retains all Customer capabilities.
7. An Artist may purchase artwork from other Artists.
8. An Artist may commission another Artist.
9. An Artist may not purchase their own Listings.
10. An Artist may not commission themselves.
11. There is one Admin account for the current system.
12. Moderator is a planned staff role, but for the current system the Admin performs moderation responsibilities.
13. Staff authentication is separate from normal Customer authentication.
14. Customers may request deletion of their accounts.
15. Account deletion must not destroy Orders, Commissions, Payments, Refunds, Payouts, disputes, or other records necessary to preserve historical transactions.
16. Personal/current account information may be logically deleted or anonymized where appropriate while necessary historical transaction snapshots remain preserved.
17. A deleted account releases its email for new registration. A later registration creates a new User identity; historical transactions remain attached to the original User ID and retained snapshots.
18. A Customer may save multiple addresses and designate at most one active default address. Saved addresses are current, editable data.

---

# 2. Artist Profiles and Artist Status

1. A User may have at most one Artist Profile.
2. Only the Admin may authorize/create an Artist Profile.
3. An Artist Profile has two operational statuses:
   - `ACTIVE`
   - `SUSPENDED`
4. Only ACTIVE Artists may publish Listings for sale.
5. Only ACTIVE Artists may accept new Commission requests.
6. An ACTIVE Artist may independently configure whether they are currently open or closed to Commission requests.
7. Suspending an Artist must not delete their Artwork, Orders, Commissions, financial records, messages, deliverables, or other historical records.
8. A suspension has a start time and may have an end time.
9. A suspension without an end time represents an indefinite suspension.
10. A timed suspension automatically returns the Artist to ACTIVE when the suspension expires unless the suspension has been changed or extended.
11. A suspended Artist may submit an appeal to the Admin.
12. Appeals and their outcomes must be retained.
13. When an Artist becomes SUSPENDED, their currently published Listings automatically become unavailable for new purchases.
14. Suspension does not remove the Artist's Customer capabilities.
15. A suspended Artist may continue purchasing Artwork and requesting Commissions from other Artists.
16. If an Artist is suspended while already performing an `IN_PROGRESS` Commission, the Artist may continue working on and delivering that existing Commission.
17. Admin actions affecting Artist status, suspension and appeals must be auditable.

---

# 3. Artwork

1. Every Artwork belongs to exactly one Artist.
2. Only the owning Artist may normally modify their Artwork.
3. The Admin may perform authorized moderation actions on Artwork.
4. Artwork is primarily classified as either `2D` or `3D`.
5. Artwork may contain multiple media/files.
6. Public preview media must be distinguishable from protected source/purchased files.
7. Artwork may be associated with zero or more controlled Tags.
8. Tags come from an Admin-managed controlled vocabulary.
9. Artists select existing Tags rather than creating arbitrary public Tags.
10. A separate Category taxonomy is not required for the current system.
11. Artwork does not require Admin approval before publication.
12. Artwork may nevertheless be moderated independently of its Artist's status.
13. Artwork may remain publicly visible even when it has no currently purchasable Listings.
14. Artwork purchase availability is determined by whether it has at least one currently published and purchasable Listing.
15. Removing Artwork or its Listings from sale must not alter or remove historical transactions involving them.
16. Historical purchases must remain understandable even if the current Artwork is subsequently edited, unpublished, moderated, archived, or logically deleted.
17. After initial publication, the underlying creative work/source content cannot be replaced with a materially different work; publish that revision as a new Artwork.
18. New commercial Listings, including additional physical sizes or formats, may be added to an already published Artwork.
19. Non-substantive metadata corrections may be made; historical Orders retain the original purchase snapshots.

---

# 4. Listings

1. Every Listing references exactly one Artwork.
2. An Artwork may have zero or more Listings.
3. Each Listing represents one particular commercial offering of the Artwork.
4. A Listing may be either:
   - Digital; or
   - Physical.
5. Only published and currently purchasable Listings may be purchased.
6. An Artist must be ACTIVE for their Listings to be available for new purchases.
7. All marketplace prices are denominated in VND for the current version of the system.
8. The system should not depend conceptually on VND being the only currency forever, but foreign-currency transactions and currency conversion are outside the current scope.
9. Different physical variants are represented as separate Listings rather than through a generalized variant system.
10. Therefore, different sizes, materials, manufacturing specifications or prices may each be represented by separate Listings.
11. Inventory management is outside the scope of the system.
12. Listings do not maintain stock quantities or inventory reservations.
13. Changes to a Listing's current price or specifications must never alter previous Orders.
14. Published commercial digital files cannot be overwritten or replaced in place. A materially different digital offering is published as a new Artwork/Listing; the old offering may be unlisted.
15. Each physical Order Item quantity is fulfilled as one unit of workflow; different Order Items may have different states.

---

# 5. Digital Listings and Assets

1. A Digital Listing includes one or more protected, immutable asset files.
2. A direct Digital Listing is purchasable only with quantity `1`.
3. Confirmed full payment grants the purchasing Customer a permanent entitlement to the exact files included when the Order was created.
4. Published commercial files and their storage objects may not be overwritten or removed while historically entitled purchasers depend on them. New material content is a new Artwork/Listing.
5. Public preview files are separate from protected purchased files.
6. Unlisting does not revoke an already granted entitlement.
7. Direct digital purchases are normally non-refundable after successful payment. No post-payment Admin refund exception is included in the normal schema scope.

---

# 6. Physical Listings

1. Physical Listings represent externally manufactured physical versions of Artwork.
2. A physical purchase includes a digital copy, but grants no digital download entitlement at payment time.
3. The digital entitlement to the exact included files is granted only when that physical Order Item is confirmed `DELIVERED`.
4. Ordinary physical-item refunds are available only before confirmed delivery. Delivery closes the ordinary refund path and makes Artist proceeds payout-eligible.
5. Physical Listings may be purchased in quantities greater than one. All copies within a single Order Item share one fulfillment state.
6. Each commercially different physical variant is a separate Listing with the specifications needed by the external manufacturer.
7. No inventory or manufacturer-internal production system is modeled.
8. Fulfillment states include `PENDING`, `IN_PRODUCTION`, `READY_TO_SHIP`, `SHIPPED`, `DELIVERED`, with cancellation/failure states as needed.
9. Different physical Order Items may progress separately even when shipped to one address. Package/carrier operations are outside scope.

---

# 7. Physical Pricing and Manufacturing Costs

1. The marketplace/manufacturer provides the Artist with the current Manufacturing Cost of a physical product.
2. The Artist chooses an Artist Markup.
3. The physical Listing's Retail Price is:

   **Manufacturing Cost + Artist Markup = Retail Price**

4. Shipping is charged separately from the Retail Price.
5. Applicable tax is accounted for separately.
6. The marketplace's Platform Fee is calculated against the Artist Markup rather than against Manufacturing Cost or shipping.
7. Therefore:

   **Platform Fee = Global Platform Fee Rate × Artist Markup**

8. Artist Proceeds are:

   **Artist Markup − Platform Fee = Artist Proceeds**

9. Manufacturing Cost is not considered Artist income.
10. Shipping charges are not considered Artist income.
11. When the manufacturer's base Manufacturing Cost changes, the marketplace must not silently alter the Artist's published Retail Price.
12. Instead, the affected Physical Listing becomes unavailable for new purchases until the Artist reviews the new Manufacturing Cost and confirms or changes their Markup.
13. Manufacturing Cost, Artist Markup, Retail Price, Platform Fee rate, Platform Fee amount and Artist Proceeds applicable to an existing Order must never be recalculated using later values.

---

# 8. Cart and Checkout

1. A Customer may maintain a Cart before purchasing.
2. A Cart may contain Listings belonging to multiple Artists.
3. A Cart may contain both Digital and Physical Listings.
4. Cart contents do not reserve inventory.
5. Adding a Listing to a Cart does not lock its price.
6. If a Listing's price changes before checkout, checkout uses the current price.
7. The Customer must be informed of relevant price changes before committing to payment.
8. Transaction prices become fixed only when the Order is created.
9. Checkout may create one Order containing items belonging to multiple Artists.
10. The system does not require separate Orders merely because Listings belong to different Artists.
11. Artist financial attribution occurs at the individual purchased-item level.

---

# 9. Orders and Order Items

1. An Order belongs to exactly one purchasing Customer.
2. An Order contains one or more purchased items.
3. An Order may contain items belonging to multiple Artists.
4. An Order may contain Digital Listings, Physical Listings, or both.
5. Each purchased item must preserve the Artist responsible for that item.
6. An Order represents one checkout transaction even when fulfillment differs between its items.
7. A mixed Order may therefore have digital items already fulfilled while physical items remain in production or transit.
8. The system must not assume that every item within an Order has the same fulfillment state.
9. A successful payment covering an Order covers the complete Order amount.
10. Following successful payment:
   - direct Digital Listing entitlements become available immediately; and
   - physical items begin manufacturing/fulfillment, with their included digital files withheld until delivery.
11. Physical fulfillment does not require a second Customer payment.
12. An Order is created before payment, snapshots its prices, and may have multiple payment attempts. It expires after a configurable short payment window.
13. Listing availability and price are validated atomically at Order creation. Later unlisting does not invalidate a pending Order; payment may complete until its expiration.
14. One Order containing physical items has one shipping address.

---

# 10. Historical Order Snapshots

An Order must preserve the transaction as it existed when the purchase occurred.

At minimum, historical information must preserve, where applicable:

1. Artwork identity/title as purchased.
2. Listing identity/name as purchased.
3. Artist/seller identity or display information necessary to understand the transaction.
4. Quantity.
5. Unit price.
6. Manufacturing Cost.
7. Artist Markup.
8. Retail Price.
9. Platform Fee rate.
10. Platform Fee amount.
11. Artist Proceeds.
12. Tax amount.
13. Shipping amount.
14. Total amount.
15. Currency.
16. Physical specifications/selected variant represented by the purchased Listing.
17. Shipping address.
18. Relevant billing information.
19. Exact immutable digital files included with the purchased offering, even when entitlement is granted later.

Subsequent modifications to Customers, Artists, Artwork, Listings, manufacturing costs, addresses, platform fees, Tags, files or other current information must not rewrite these historical facts.

---

# 11. Vietnamese Shipping

1. Physical Listings may only be delivered within Vietnam.
2. Shipping support is configurable at the province/municipality level.
3. The marketplace maintains which Vietnamese provinces/municipalities are currently supported.
4. A physical Order may only be placed using an address in a currently supported delivery area.
5. Nationwide delivery can be represented simply by marking all applicable Vietnamese provinces/municipalities as supported.
6. The exact shipping address used for a completed Order must be preserved as a historical snapshot.
7. Later changes to the Customer's saved address must not alter previous Orders.
8. Shipping charges are recorded separately from product prices.
9. Carrier-specific logistics and internal courier operations are outside the marketplace's scope.
10. The system may record shipment information such as carrier, tracking reference and shipment status.
11. A physical Order uses one immutable shipping-address snapshot copied from the selected saved address at checkout.
12. Shipping is one immutable Order-level charge; it is not allocated across Order Items.

---

# 12. Payments

1. Actual payment processing is performed through an external payment provider.
2. Orders and paid Commissions require full payment.
3. Installments and partial payments are not supported.
4. A transaction may have multiple payment attempts.
5. Failed payment attempts may be retained.
6. Fulfillment, digital entitlement, or paid Commission work must not begin merely because a payment was attempted.
7. The marketplace must receive confirmation of successful payment.
8. External payment transaction/provider references must be preserved.
9. Duplicate or retried payment notifications must not create duplicate payments, Orders, digital entitlements, or Commission state transitions.
10. Payment processing must therefore be idempotent from the marketplace's perspective.
11. For a mixed Order, one successful payment grants direct digital entitlements immediately; physical-item entitlements await delivery.
12. Expired pending Orders cannot normally be paid; a late provider success is reconciled as an exception without silently granting fulfillment or entitlements.

---

# 13. Tax

1. Detailed Vietnamese tax/VAT determination is outside the current project scope.
2. The marketplace nevertheless records the tax amount applied to each finalized transaction.
3. Tax may initially be zero where appropriate.
4. Tax must remain separately identifiable from product price, manufacturing cost and shipping.
5. Historical tax amounts must not be recalculated if tax rules later change.

---

# 14. Platform Fees and Artist Proceeds

1. The marketplace charges Artists a Platform Fee.
2. The Platform Fee rate is global for the current system.
3. Changing the global Platform Fee rate applies only to future transactions.
4. Every transaction preserves the Platform Fee rate and amount that actually applied to it.
5. Digital-sale Artist Proceeds are the digital selling price less the applicable Platform Fee.
6. Physical-sale Artist Proceeds are the Artist Markup less the applicable Platform Fee.
7. Commission Artist Proceeds are based upon the paid Commission amount less the applicable Platform Fee.
8. Artist proceeds attributable to different Artists within one Order must remain separately identifiable.

---

# 15. Artist Payouts

1. Actual movement of payout funds may be performed by an external payment/payout provider.
2. The marketplace tracks Artist payout obligations and payout status.
3. Payouts may progress through states such as:
   - `PENDING`
   - `PROCESSING`
   - `PAID`
   - `FAILED`
4. Artist proceeds from a direct Digital purchase become payout-eligible after successful payment has been confirmed and permanent digital entitlement has been granted.
5. Artist proceeds from a Physical purchase become payout-eligible after the physical item has been delivered.
6. Artist proceeds from a Commission become payout-eligible after the Commission becomes `COMPLETED`.
7. Artist payout calculations must use the historical financial values associated with the relevant transaction.
8. Under the normal refund workflow, a refund may not be approved after the corresponding Artist proceeds have already been paid out.
9. Exceptional post-payout financial correction is an Admin matter outside the normal automated refund/payout workflow.

---

# 16. Refunds for Normal Orders

1. Customers may request refunds for ordinary purchases.
2. Refund requests are manually reviewed.
3. Refunds operate at the affected Order Item level rather than necessarily refunding the entire Order.
4. Arbitrary partial monetary refunds within an individual Order Item are not supported.
5. An approved item refund refunds the full refundable amount associated with the affected item.
6. Multiple affected items may each be fully refunded where appropriate.
7. A refund request may be approved or rejected.
8. Refund decisions must be preserved historically.
9. Shipping is refunded only when the entire physical portion of the Order is refunded.
10. Refund approval is normally unavailable once the associated Artist proceeds have already been paid out.
11. Direct digital Order Items are non-refundable after payment. Physical Order Items are ordinarily refundable only before confirmed delivery.
12. Delivery grants the included digital entitlement and closes the normal physical refund path. No post-delivery Admin refund exception is part of this rule set.
13. A single Order-level shipping charge is refundable only after all physical Order Items have been fully refunded, and only once.

---

# 17. Commission Availability and Requests

1. Only ACTIVE Artists may accept new Commission requests.
2. ACTIVE Artists may configure themselves as open or closed to new Commission requests.
3. An Artist may define a Commission starting price for informational purposes.
4. A Customer may submit a Commission request only to an Artist who is ACTIVE and currently accepting requests.
5. A Commission request must contain a description of the requested work.
6. Customers may attach reference materials.
7. Artists may commission other Artists.
8. Artists may not commission themselves.
9. Suspending an Artist prevents new Commission requests/acceptances but does not automatically terminate existing active Commissions.

---

# 18. Commission Messaging

1. Every Commission provides a message thread between the Customer and commissioned Artist.
2. Messages support discussion, negotiation, clarification and discussion of revisions.
3. Messages do not directly modify agreed Commission terms.
4. Messages do not themselves alter Commission lifecycle state.
5. Commission messages cannot be edited after being sent.
6. Formal commercial changes must be represented through Quotes or other appropriate formal actions rather than inferred from chat messages.
7. Commission messaging remains available while a Quote, Cancellation Request or other formal action is pending.
8. Pending formal actions may prevent incompatible formal actions or lifecycle transitions until resolved, withdrawn, or replaced.

---

# 19. Commission Quotes

1. Commission terms are formally proposed through Commission Quotes.
2. Only the commissioned Artist may issue a Quote.
3. A Quote must specify at minimum:
   - price;
   - agreed scope/deliverable;
   - expected completion terms/date.
4. There is no fixed revision allowance.
5. Quote expiration is not required.
6. The Artist may withdraw an unaccepted Quote.
7. The Customer may accept or decline a Quote.
8. Negotiation about changing a Quote may occur through Commission messages.
9. The Artist issues another Quote when formal terms need to change.
10. An accepted Quote becomes immutable.
11. An accepted Quote requiring payment does not become paid merely because it was accepted.
12. Successful payment must be separately confirmed.
13. A Quote may have a price of `0 VND` when both parties need to formally record a scope change that does not require additional payment.

---

# 20. Initial Commission Payment

1. The initial accepted paid Quote establishes the initial paid Commission scope.
2. Full successful payment of the initial required amount must occur before the Commission enters `IN_PROGRESS`.
3. Multiple payment attempts may occur.
4. Only confirmed successful payment permits the paid work to begin.
5. If the initial Quote has not yet been paid, either party may cancel without invoking a refund because no successful payment has occurred.

---

# 21. Commission Scope Changes / Additional Quotes

1. Accepted Quotes are never modified to represent later changes.
2. New requirements arising after work begins may be formalized through additional Quotes.
3. A Commission may therefore have multiple accepted Quotes.
4. Each accepted Quote remains independently preserved as part of the agreed Commission history.
5. An additional Quote may require additional payment.
6. Previously paid money remains associated with the previous accepted Quote(s); it is not rewritten as though the later Quote existed originally.
7. An additional paid scope becomes binding after the Quote is accepted and its required payment is successfully completed.
8. A `0 VND` Quote may formalize an agreed scope change without requiring payment.
9. While an additional Quote is awaiting acceptance/payment, the Artist remains responsible for the already accepted and paid scope.
10. Rejecting an additional Quote leaves the previously agreed scope unchanged.

---

# 22. Commission Deliverables and Revisions

1. Only the commissioned Artist may submit Commission deliverables.
2. A Commission may have multiple deliverable submissions/versions.
3. Previous submitted deliverable versions are retained as immutable historical records.
4. Submitted deliverables may contain one or more files.
5. After a deliverable is submitted, the Commission enters an appropriate Customer review state.
6. The Customer may approve the submitted deliverable.
7. The Customer may instead request changes/revision while the Commission is in the appropriate review state.
8. Revision requests are not subject to a predefined numerical allowance.
9. Revision discussions may occur through the Commission message thread.
10. Formal revision requests and subsequent submissions remain historically identifiable.
11. The responsibility for determining when repeated revisions require cancellation, dispute, or a newly paid scope change rests with the parties and the applicable marketplace processes.
12. A Commission becomes `COMPLETED` when the Customer approves the final deliverable.
13. Completed Commission records and deliverables must be retained.

---

# 23. Commission Lifecycle

The Commission lifecycle must support at least the concepts represented by:

`REQUESTED → QUOTED / awaiting agreement → awaiting required payment → IN_PROGRESS → SUBMITTED_FOR_REVIEW → COMPLETED`

It must also support appropriate transitions involving:

- `DECLINED`
- revision requested / return to work;
- cancellation;
- dispute.

Lifecycle transitions must be preserved historically where required for auditability and dispute investigation.

A Commission must not enter `IN_PROGRESS` until the required initial payment has successfully completed.

---

# 24. Commission Cancellation

1. Either Customer or Artist may request cancellation of an active Commission.
2. A Cancellation Request is a formal action rather than an ordinary chat message.
3. The other party may accept or reject the Cancellation Request.
4. The requester may withdraw a still-pending Cancellation Request before the other party responds.
5. If both parties agree, the Commission is cancelled.
6. Rejection of cancellation does not itself cancel the Commission.
7. The parties may continue communicating while cancellation is pending.
8. Conflicting formal lifecycle actions may be restricted while a Cancellation Request is pending.
9. A rejected or withdrawn Cancellation Request remains part of the Commission's history.
10. The Admin may force cancellation when resolving a dispute.
11. Cancellation and refund are separate concepts.
12. Cancelling a paid Commission does not automatically issue a refund.
13. After cancellation, the Customer may request a refund.

---

# 25. Commission Refunds

1. Commission refunds are manually reviewed.
2. A refund request may be approved or rejected.
3. Partial Commission refunds are not supported.
4. If a full Commission refund is approved, it covers the total refundable amount paid across all accepted and paid Quotes belonging to that Commission.
5. This includes payments for later accepted change/scope Quotes.
6. An approved refund does not rewrite or delete the accepted Quotes or payment history.
7. Refund history must be preserved.
8. Under the normal workflow, refunds may not be approved after the corresponding Artist proceeds have already been paid out.

---

# 26. Commission Disputes

1. Either the Customer or Artist may open a dispute.
2. A Commission may have at most one active dispute at a time.
3. A dispute may progress through:
   - `OPEN`
   - `UNDER_REVIEW`
   - `RESOLVED`
4. The Admin reviews disputes.
5. Dispute resolution may result in outcomes including:
   - continuation of the Commission;
   - cancellation with refund;
   - cancellation without refund.
6. A dispute outcome is distinct from the Commission's lifecycle state, although resolving the dispute may cause a lifecycle transition.
7. Admin dispute actions and resolutions must be auditable.
8. Dispute history must be retained.
9. Messages, Quotes, deliverable versions, revision requests, cancellation requests and payment history must remain available as historical evidence for dispute review.

---

# 27. Files and Storage

1. Large Artwork, 2D source files, 3D models, Commission attachments and deliverables should be stored in external object/file storage rather than directly as large binary data inside the relational database.
2. The marketplace retains metadata necessary to identify and authorize those files.
3. File metadata may include filename, storage identifier, media/content type, size, checksum and creation information.
4. Public preview files and protected source files have different access rules.
5. Purchased Digital Assets require authorization based upon the Customer's entitlement.
6. Commission files require authorization based upon Commission participation or authorized Admin access.
7. Historical purchased asset files and Commission deliverable versions must remain identifiable. Commercial files of published Listings are immutable; Commission deliverables may have successive immutable submissions.

---

# 28. Deletion and Historical Retention

1. Business-critical historical records should normally be retained rather than physically deleted.
2. Logical deletion, archival, deactivation or anonymization should be preferred where historical relationships must survive.
3. Deleting/deactivating a Customer must not delete their historical Orders, payments, refunds or Commissions.
4. Suspending/deactivating an Artist must not delete their historical Artwork transactions, Commissions, payments, payouts or financial records.
5. Removing an Artwork or Listing from public availability must not remove it from historical Orders.
6. Completed Commission records must remain available even if either participant later becomes inactive.
7. Financial history must remain internally consistent even when related current-state entities change.
8. Purely temporary/unreferenced records and files may be physically deleted when no historical or business requirement requires retention.

---

# 29. Admin Auditing

1. The system does not require exhaustive auditing of every ordinary Customer or Artist edit.
2. Sensitive Admin actions must be auditable.
3. Sensitive actions include at minimum:
   - Artist suspension/reinstatement;
   - Artist appeal decisions;
   - Artwork moderation;
   - refund decisions;
   - dispute review/resolution;
   - Admin-forced Commission cancellation;
   - other exceptional financial or moderation interventions.
4. Audit information must identify the action, affected business object, responsible Admin, timestamp and sufficient reason/context where applicable.
5. Audit/history records must not disappear merely because the affected operational record becomes inactive.

---

# 30. Historical and Immutable Information

The system follows the general rule:

> **Current records describe what is true now; transaction records describe what was true when an event occurred.**

Accordingly:

1. Current non-substantive Artwork metadata may change; published underlying creative content cannot be materially replaced.
2. Current Listing information and pricing may change.
3. Current Manufacturing Costs may change.
4. The global Platform Fee may change.
5. Customers may change their profile and saved addresses.
6. Artists may change their profile.
7. Public preview files may change. Published commercial files cannot be overwritten; new material offerings use new Artwork/Listing records.

None of these changes may retroactively alter historical transactions.

Accepted Commission Quotes, completed payments, historical Order pricing, purchased immutable asset files, financial calculations, shipping-address snapshots, submitted Commission deliverables and completed refund/payout information must preserve their historical meaning.

---

# 31. Transaction and Concurrency Integrity

1. A successful payment must never be processed twice because the payment provider retries a notification.
2. One payment event must not grant the same digital entitlement multiple times.
3. Concurrent payment events must not cause a Commission to enter the same lifecycle stage multiple times.
4. Commission lifecycle transitions must only occur from valid preceding states.
5. A Customer may request a revision only while the Commission is in an appropriate review state.
6. Only the commissioned Artist may submit deliverables.
7. Only Commission participants may perform participant-specific Commission actions.
8. Only one active dispute may exist for a Commission.
9. A formal action that has already been accepted, rejected, withdrawn, completed, or otherwise resolved must not be processed a second time.
10. Order creation must use a consistent set of prices and financial values and preserve those values once the purchase is established.
11. Artist payout eligibility must not be generated more than once for the same underlying proceeds.
12. Refund processing must not produce duplicate refunds.
13. The eventual implementation must enforce important financial and lifecycle invariants at the database/transaction level where application-level checks alone could permit race conditions.

---

## Additional implementation decisions

1. Payments for Orders and paid Commission Quotes use one payment subsystem with an exclusive payable target. A zero-price Quote requires no payment record.
2. Each paid Quote independently snapshots its fee and Artist proceeds; later Quotes do not recalculate prior Quote financials.
3. Artist proceeds are separate obligations linked to the source Order Item or paid Quote. Payouts may carry one or many obligation lines; initial operation may use one line per transfer.
4. Financial money amounts use `decimal(19,4)` and fee rates use `decimal(9,6)` in SQL Server; currency is recorded explicitly, initially `VND`.
5. A physical item quantity is refunded as one whole Order Item. Customer saved addresses are separate from Order address snapshots.
6. Sensitive Admin actions and lifecycle changes have historical event/audit records.

---

## Final scope boundary

The marketplace **does** manage Customers, Artists, Artwork, Listings, controlled Tags, carts, Orders, digital entitlements, manufacturing/fulfillment status, supported Vietnamese delivery areas, payments as marketplace records, platform fees, Artist proceeds/payout tracking, refunds, Commissions, Quotes, scope changes, deliverables, revisions, cancellations, disputes and sensitive Admin auditing.

It **does not** attempt to implement inventory management, arbitrary product variants, foreign-currency conversion, installment payments, partial monetary refunds within an item or Commission, detailed Vietnamese tax determination, courier logistics, manufacturer factory operations, payment processing infrastructure, or banking infrastructure.

This is the finalized business-rules baseline to carry into the separate database-architecture discussion.