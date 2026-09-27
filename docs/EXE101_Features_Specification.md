# **Marketplace software feature specifications**

Source: `Marketplace_Business_Rules_Final.md` (26 September 2026), checked against `EXE101_SQL_Schema.sql` (SQL Server 2019+). Rule references use the numbered sections of the business rules; `Add.` means Additional implementation decisions. The SQL is an implementation baseline, not an additional source of business policy. “Needs Clarification” marks decisions that cannot safely be inferred.

| ID | Feature | Primary domain |
| ----- | ----- | ----- |
| F01 | Customer identity and account lifecycle | Users |
| F02 | Staff authentication and artist authorization | Staff, artists |
| F03 | Artist availability, suspension and appeals | Artists |
| F04 | Customer address book and supported delivery areas | Shipping |
| F05 | Artwork, preview media and controlled tags | Catalog |
| F06 | Digital and physical listing management | Catalog |
| F07 | Manufacturing cost review and fee configuration | Pricing |
| F08 | Protected file storage and authorization | Files |
| F09 | Cart and checkout quotation | Cart |
| F10 | Order creation and immutable snapshots | Orders |
| F11 | Provider payment and reconciliation | Payments |
| F12 | Digital entitlements | Downloads |
| F13 | Physical fulfillment | Fulfillment |
| F14 | Artist proceeds and payouts | Finance |
| F15 | Order item refunds and shipping refunds | Refunds |
| F16 | Commission requests and messages | Commissions |
| F17 | Commission quotes and scope changes | Commissions |
| F18 | Commission delivery, review and revisions | Commissions |
| F19 | Commission cancellation and dispute resolution | Commissions |
| F20 | Commission refunds | Refunds |
| F21 | Admin audit and historical retention | Governance |

## **B. Feature specifications**

### **F01 — Customer identity and account lifecycle**

* **Purpose:** Give each registered person a stable Customer identity while allowing deletion without erasing transactions.  
* **Rules:** §1.1–18; §28.1–3; §30.5.  
* **Functional requirements:** The system must register an email/password user, authenticate them, and permit every user to act as a Customer. It must prevent reuse of an active normalized email. It must let a Customer request account deletion, remove/restrict current credentials and identifying profile data as appropriate, release the email, and keep the original User ID and necessary transaction snapshots. Re-registration with the same email must create a new User ID.  
* **Inputs → outputs:** Email, password, display name → active User; deletion request → deleted/anonymized User with retained transaction links; login credentials → authenticated Customer or rejection.  
* **Decision logic:** IF normalized email belongs to a current account THEN reject registration ELSE create a new User. IF deletion is processed THEN disable login and release email while preserving historical records ELSE keep account active.  
* **Validation:** Email/password required; email unique among nondeleted accounts; normalize consistently. Password policy, verification and blocked-account behavior: **Needs Clarification**.  
* **Edge cases:** Duplicate registration race; account deleted while checkout or commission is active; repeat deletion; historical records referring to a deleted User. Keep operations transactional and idempotent.  
* **Acceptance criteria:** Given a deleted Customer with an old Order, when the same email registers again, then the new User ID differs and the old Order still refers to the original identity. Given an active normalized email, when another registration uses it, then registration fails.  
* **Implementation:** `MarketplaceUser`, `CustomerAddress` and preserved snapshots; registration/login/deletion operations, unique normalized-email index, password hash, authorization checks; define deletion workflow before production.

### **F02 — Staff authentication and artist authorization**

* **Purpose:** Separate staff access and ensure Artists exist only by Admin authorization.  
* **Rules:** §1.5–13; §2.1–2,17; §29.  
* **Functional requirements:** The system must authenticate staff separately from Customers. The current system has one Admin account; Moderator is planned but Admin performs moderation. Only Admin may grant an Artist Profile, at most one per User. An Artist retains Customer permissions, may buy from other Artists and commission other Artists, but must not buy their own Listing or commission themselves.  
* **Inputs → outputs:** Staff credentials, target User, artist display data → authenticated staff session/Artist Profile or denial.  
* **Decision logic:** IF actor is Admin and target has no Artist Profile THEN create one ELSE reject. IF buyer/requester User ID equals owning Artist's User ID THEN reject self transaction ELSE continue.  
* **Validation:** Target User and Artist display name required; one Artist Profile per User; one active Admin account. Account provisioning/recovery: **Needs Clarification**.  
* **Edge cases:** Concurrent grants; artist purchasing in a mixed cart containing their own item; deleted/blocked target user; staff role escalation.  
* **Acceptance criteria:** Given a User without Artist Profile, when Admin authorizes them, then one Artist Profile is created and Customer access remains. Given an Artist's own Listing among cart items, when checkout is attempted, then it is rejected.  
* **Implementation:** `StaffAccount`, `ArtistProfile`, `AdminAudit`; distinct staff/customer authentication endpoints and role checks; unique ArtistProfile.UserId.

### **F03 — Artist availability, suspension and appeals**

* **Purpose:** Block new Artist commerce during suspension without destroying existing commitments.  
* **Rules:** §2.3–17; §3.12; §4.6; §17.1–2,9; §29.  
* **Functional requirements:** Admin must suspend/reinstate Artists for finite or indefinite periods with reasons and audit events. Suspension must make existing published Listings unavailable for new Orders and block new Commission requests/acceptances. The Artist retains Customer access and may continue an already `IN_PROGRESS` Commission. An Artist may appeal; Admin may decide and retain appeal history. A timed suspension must expire automatically unless changed or extended.  
* **Inputs → outputs:** Artist, start/end, reason, appeal and decision → status, availability, history/audit.  
* **Decision logic:** IF status is SUSPENDED THEN deny new purchase/request/acceptance for that Artist ELSE apply other availability checks. IF current suspension reaches its end and is still current THEN reactivate and record transition ELSE do nothing.  
* **Validation:** End after start if supplied; only Admin changes status; appeal tied to suspension; only active suspension auto-expires.  
* **Edge cases:** Expiry racing an extension; checkout racing suspension; appeal after expiry; existing pending request or quote on suspension: **Needs Clarification**.  
* **Acceptance criteria:** Given a published Listing, when its Artist is suspended, then a new checkout fails but an earlier unexpired Order remains governed by its Order terms. Given an unchanged timed suspension, when its end arrives, then the Artist returns to ACTIVE once.  
* **Implementation:** `ArtistProfile`, `ArtistSuspension`, `ArtistAppeal`, audit; availability computed from status rather than rewriting every Listing; scheduler with conditional update and audit.

### **F04 — Customer address book and supported delivery areas**

* **Purpose:** Limit physical delivery to supported Vietnamese provinces while preserving historical addresses.  
* **Rules:** §1.18; §11.1–12; §9.14.  
* **Functional requirements:** Customers must manage multiple current saved addresses and at most one active default. Admin must maintain province/municipality support. Physical checkout must use one selected saved address in Vietnam and copy it to one immutable Order address snapshot. Shipping must be a distinct immutable Order-level charge.  
* **Inputs → outputs:** Address fields, province code, default flag, support configuration → saved address, eligibility result, OrderShippingAddress.  
* **Decision logic:** IF Order has physical items AND selected province is currently supported AND country is VN THEN permit shipping snapshot ELSE reject physical checkout. IF no physical items THEN no shipping address is required.  
* **Validation:** Recipient, phone, address line and province required by schema; one active default per User; country VN; province must exist and be supported at checkout. Exact address/phone formats: **Needs Clarification**.  
* **Edge cases:** Province support changes after Order creation; saved address changes/deletion; concurrent default-address changes; a mixed Order still has only one address.  
* **Acceptance criteria:** Given a supported province and physical cart, when Order is created, then one address snapshot is retained. When the saved address changes afterward, the Order snapshot does not change.  
* **Implementation:** `CustomerAddress`, `SupportedProvince`, `OrderShippingAddress`; address CRUD/admin support APIs; transactionally select current support and copy address.

### **F05 — Artwork, preview media and controlled tags**

* **Purpose:** Let Artists publish discoverable 2D/3D works without rewriting past sales.  
* **Rules:** §3.1–19; §27; §28.5; §30.1,7.  
* **Functional requirements:** An Artwork belongs to one Artist; owner edits it, Admin moderates it. Artists may create drafts, publish without approval, attach multiple preview media and select existing Admin-managed tags. Published work may remain visible with no purchasable Listing. Metadata corrections are allowed; materially different creative/source content must be a new Artwork. Unlisting/moderation/archival must preserve historical references.  
* **Inputs → outputs:** Title, 2D/3D class, media, tag IDs, status → Artwork and catalog visibility.  
* **Decision logic:** IF Artwork is publicly visible THEN show it even with zero available Listings, while purchase availability is false; IF moderation removes availability THEN reject new purchases regardless of Listing status.  
* **Validation:** One owning Artist; class `2D` or `3D`; tags exist in controlled vocabulary; unique media ordering per Artwork. Required preview count and public visibility by exact status: **Needs Clarification**.  
* **Edge cases:** Listing published under subsequently moderated Artwork; tag deactivated after selection; owner attempts material source replacement; simultaneous metadata edits.  
* **Acceptance criteria:** Given published Artwork with no Listings, when viewed, then it can be visible and cannot be bought. Given a prior Order, when Artwork title changes, then the Order retains its purchased title.  
* **Implementation:** `Artwork`, `ArtworkMedia`, `Tag`, `ArtworkTag`, `StoredFile`; owner/admin APIs; status and moderation audit; row-version concurrency.

### **F06 — Digital and physical listing management**

* **Purpose:** Model each purchasable offering as a Listing, including new physical sizes under an existing Artwork.  
* **Rules:** §3.18; §4.1–15; §5.1–2,4–5; §6.1,5–7.  
* **Functional requirements:** Owner may create multiple Listings per Artwork, each `DIGITAL` or `PHYSICAL`; physical commercial variants are separate Listings. Only published purchasable Listings of ACTIVE Artists may enter new Orders. Digital Listings require one or more immutable protected files and quantity one. Physical Listings carry manufacturing specifications and can have quantity above one. Unlisting does not alter Orders. Published commercial digital assets must not be replaced in place.  
* **Inputs → outputs:** Artwork ID, type, name, VND price or cost/markup, specifications, protected files, status → Listing and availability.  
* **Decision logic:** IF Listing, Artwork, and Artist satisfy publication/moderation/availability and physical cost review is complete THEN offer for checkout ELSE unavailable. IF DIGITAL THEN quantity must equal 1 ELSE quantity may exceed 1\.  
* **Validation:** Type enum, nonnegative prices, physical cost \+ markup \= retail, physical specifications present, digital protected asset count ≥1 at publication; no stock field or reservation.  
* **Edge cases:** Publish while artist suspension begins; file missing in storage; existing pending Order after unlisting; adding a new A4 or A0 Listing to an already published Artwork.  
* **Acceptance criteria:** Given published Artwork, when its owner adds and publishes a new A4 physical Listing, then the old Listings remain intact. Given a digital Listing, when quantity two is submitted, then checkout rejects it.  
* **Implementation:** `Listing`, `ListingAsset`, `Artwork`, `StoredFile`; draft/publish/unlist/moderate operations; publish transaction and immutable storage policy.

### **F07 — Manufacturing cost review and fee configuration**

* **Purpose:** Protect Artist control over physical prices and preserve correct fee bases.  
* **Rules:** §7.1–13; §14.1–8; §30.2–4; Add.2,4.  
* **Functional requirements:** Marketplace/manufacturer supplies current manufacturing cost; Artist sets markup. Retail \= cost \+ markup, separate from shipping/tax. On base-cost change, mark affected physical Listings unavailable until Artist reviews new cost and confirms or changes markup; never silently alter published retail price. Admin configures a global prospective fee rate. Digital fee applies to digital selling price; physical fee applies to markup; commission fee applies to paid quote price. Snapshot fee rate, fee amount and proceeds at the transaction.  
* **Inputs → outputs:** Cost update, markup, effective fee rate → review-required Listing or confirmed retail price; financial snapshots.  
* **Decision logic:** IF cost changes THEN require Artist review and block new Orders; IF Artist confirms THEN recompute retail from current cost and chosen markup and clear review flag. IF transaction is PHYSICAL THEN fee base \= quantity × markup ELSE fee base \= digital line price or paid Quote price.  
* **Validation:** VND; money `decimal(19,4)`, fee rate `decimal(9,6)` in \[0,1\], nonnegative cost/markup; applied fee must not exceed its base. Rounding and rate effective-time boundary: **Needs Clarification**.  
* **Edge cases:** Cost update racing Order creation; repeated/older provider cost update; multiple fee-rate changes; zero markup; historical Orders must retain original values.  
* **Acceptance criteria:** Given cost 100 and markup 30, when listed, then retail is 130 and the physical fee uses 30 as its per-unit base. Given later cost 120, when update arrives, then checkout is blocked until review and no existing Order values change.  
* **Implementation:** `Listing.CostReviewRequired`, `PlatformFeeRate`, `OrderItem`, `CommissionQuote`; cost-update/admin rate operations; consistent transactional snapshots.

### **F08 — Protected file storage and authorization**

* **Purpose:** Keep previews public and purchased/commission files protected and historically identifiable.  
* **Rules:** §3.5–6,17; §5.1,4–5; §10.19; §22.2–4; §27.1–7.  
* **Functional requirements:** Store file bytes outside SQL and retain metadata/storage identity in SQL. Serve public previews independently from protected assets. Grant purchased-file access only through an entitlement to the exact recorded asset; grant Commission file access only to participants or authorized Admin. Never overwrite published commercial files or submitted deliverable versions. Keep historically referenced objects available.  
* **Inputs → outputs:** Uploaded bytes/metadata, access class, requester identity and target → immutable file record and authorized file response/denial.  
* **Decision logic:** IF PUBLIC THEN permit preview access; IF PURCHASED THEN require matching entitlement; IF COMMISSION THEN require participation or Admin authorization; ELSE deny.  
* **Validation:** Unique storage key, nonnegative size, access class; listing asset references must use protected files and public media public files, enforced by application/transaction contract (not cross-table SQL check).  
* **Edge cases:** Orphaned upload, missing object despite metadata, leaked direct object URL, attempted overwrite, deleted Customer with historical purchase; retention policy details: **Needs Clarification**.  
* **Acceptance criteria:** Given a paid digital buyer, when requesting an included file, then access succeeds. Given a physical buyer before delivery or unrelated User, when requesting the same protected file, then access is denied.  
* **Implementation:** `StoredFile`, `ArtworkMedia`, `ListingAsset`, `OrderItemAsset`, `EntitlementAsset`, Commission file tables; signed access or gated stream; immutable object keys and garbage collection only for unreferenced files.

### **F09 — Cart and checkout quotation**

* **Purpose:** Assemble mixed multi Artist purchases and show current prices before commitment.  
* **Rules:** §8.1–11; §4.11–12; §9.3–4,14.  
* **Functional requirements:** Customer may add/remove/update Cart items from multiple Artists and both types. Cart neither reserves stock nor locks price. Checkout must fetch current availability, price, tax and separate shipping, and inform Customer of relevant price changes before committing. Self purchases are rejected.  
* **Inputs → outputs:** Listing IDs/quantities, selected address → Cart, current checkout amounts or validation errors.  
* **Decision logic:** IF cart price differs from current price THEN present updated total and require informed commitment before Order creation; IF any item unavailable or own Listing THEN block that checkout.  
* **Validation:** Positive integer quantity; digital exactly one; existing Listings; supported address for physical items; Cart has at least one item to checkout.  
* **Edge cases:** Listing unlisted between view and Order creation; changed price during checkout; duplicate add; stale Cart; one invalid item among valid ones. Whether to remove or retain invalid items: **Needs Clarification**.  
* **Acceptance criteria:** Given a Cart priced at 100 and Listing now priced at 120, when checkout is reviewed, then 120 is shown before commitment; a later Order uses the price atomically checked at creation.  
* **Implementation:** `Cart`, `CartItem`; Cart CRUD and checkout preview endpoint; optimistic versioning; final validation in F10 transaction.

### **F10 — Order creation and immutable snapshots**

* **Purpose:** Freeze one complete checkout across Artists, item types and later Catalog changes.  
* **Rules:** §9.1–14; §10.1–19; §11.11–12; §13; §30; §31.10; Add.4–5.  
* **Functional requirements:** Create one Order with ≥1 item and one Customer, mixed Artists/types allowed. Atomically validate listing availability and current prices, calculate full VND amount including separate tax/shipping, snapshot item identity, specs, per-item Artist/financials and exact included files, plus billing and one physical shipping address. Set a configurable short expiration; create before payment. Later Listing edits/unlisting must not mutate the Order or invalidate it until expiration. Payment covers entire Order, with one or more attempts.  
* **Inputs → outputs:** Committed Cart, Customer, address/billing data, fee/tax/shipping values → immutable Order and item/file/address snapshots plus payment target.  
* **Decision logic:** IF all items valid at atomic creation THEN create full snapshot and pending Order ELSE create no Order. IF pending Order expires without confirmed success THEN mark expired and reject ordinary payment completion.  
* **Validation:** Quantity, VND, nonnegative components and consistent sums; exactly one physical address when needed; each item belongs to its captured Artist; exact asset set; positive payable total for provider payment. Zero-total Order handling: **Needs Clarification**.  
* **Edge cases:** Concurrent cost/status/price changes; duplicate checkout submission; partial Order creation; expiration race with callback; tax/rounding overflow.  
* **Acceptance criteria:** Given two Artists and mixed items, when checkout commits, then exactly one Order with two separately attributed items is created. Given an unlisting after creation, when successful payment arrives before expiry, then the Order can complete with its original snapshots.  
* **Implementation:** `MarketplaceOrder`, `OrderItem`, `OrderItemAsset`, `OrderShippingAddress`, `Payment`; one database transaction with isolation/locking strategy; expiry job; immutable triggers plus application permissions.

### **F11 — Provider payment and reconciliation**

* **Purpose:** Confirm full external payments once, despite retries and late notifications.  
* **Rules:** §12.1–12; §9.9–13; §20; §21.5–8; §31.1–3; Add.1.  
* **Functional requirements:** Use one payment subsystem with exactly one payable target: Order or paid Quote. Retain multiple attempts and external references. Only provider-confirmed full success changes payment to succeeded, Order to paid, or Quote to paid; attempts alone do nothing. Deduplicate provider events and downstream side effects. A 0 VND Quote needs no payment record. Late success for expired Order becomes an exception for reconciliation, without silently fulfilling or granting entitlement.  
* **Inputs → outputs:** Target/amount, provider attempt identifiers and authenticated notifications → attempts, payment status and target transitions or exception.  
* **Decision logic:** IF notification already processed THEN return previous outcome; ELSE IF amount/target/provider confirmation valid and target payable within its window THEN commit success and events once; ELSE record exception without fulfillment.  
* **Validation:** One exclusive target, positive full amount, VND, unique provider event/reference, at most one successful attempt; signature/verification and provider contract: **Needs Clarification**.  
* **Edge cases:** Concurrent callbacks; two attempts both report success; failed attempt followed by success; wrong amount; expiry callback race; provider success without known attempt.  
* **Acceptance criteria:** Given duplicate success callbacks, when both are handled, then one payment success and one set of side effects exist. Given provider success after Order expiration, then Order remains unfulfilled and an exception is recorded.  
* **Implementation:** `Payment`, `PaymentAttempt`, `ProviderEvent`; provider initiation/webhook/reconciliation endpoints; transactional unique keys and event-driven or in-transaction idempotent side effects.

### **F12 — Digital entitlements**

* **Purpose:** Grant permanent access at the correct moment to exactly the files bought.  
* **Rules:** §5.1–7; §6.2–3; §9.10; §10.19; §12.11; §31.2.  
* **Functional requirements:** On confirmed full payment, grant a direct digital Order Item entitlement to its snapshotted files. For physical items, grant entitlement only on confirmed `DELIVERED`. Entitlement survives unlisting and protects exact historical file versions. Do not grant twice.  
* **Inputs → outputs:** Paid Order or delivered physical item and OrderItemAsset set → one entitlement with asset mappings.  
* **Decision logic:** IF direct digital and Order paid OR physical and item delivered with paid Order THEN grant once ELSE withhold.  
* **Validation:** Customer equals Order buyer; exactly one entitlement per Order Item; entitlement assets match OrderItemAsset snapshots; no asset substitution.  
* **Edge cases:** Payment and delivery event retries; physical delivery recorded without paid Order; object unavailable; account deletion access policy: **Needs Clarification**.  
* **Acceptance criteria:** Given paid mixed Order, when payment confirms, then digital item downloads work but physical included files do not. When that physical item becomes delivered, then its exact files become accessible once.  
* **Implementation:** `DigitalEntitlement`, `EntitlementAsset`, `OrderItemAsset`; idempotent grant transaction and authorization layer.

### **F13 — Physical fulfillment**

* **Purpose:** Track each physical item quantity as one independent production and delivery workflow.  
* **Rules:** §4.15; §6.1–9; §9.7–11; §11.9–10; §15.5; §16.11–13.  
* **Functional requirements:** Start fulfillment only after Order payment; track each physical Order Item independently through `PENDING`, `IN_PRODUCTION`, `READY_TO_SHIP`, `SHIPPED`, `DELIVERED` or appropriate failure/cancellation. One item quantity shares a state. May record carrier/tracking/status without internal factory/courier management. On confirmed delivery grant included digital entitlement and make proceeds eligible; close ordinary physical refund route.  
* **Inputs → outputs:** Paid physical item, authorized status update, optional carrier/tracking → status/history, delivered timestamp and downstream effects.  
* **Decision logic:** IF valid prior state and authorized actor THEN record transition once; IF transition to DELIVERED THEN atomically trigger entitlement and payout eligibility ELSE retain current restrictions.  
* **Validation:** Listed status enum; delivered timestamp required only for delivered; physical item only; permitted transition graph and actor roles: **Needs Clarification**.  
* **Edge cases:** Delivery vs refund approval race; repeated delivery notification; one item failed while another delivered; tracking without carrier.  
* **Acceptance criteria:** Given paid Order with two physical items, when one is delivered, then only that item's entitlement and proceeds become eligible. Given an unpaid Order, when production start is requested, then it is rejected.  
* **Implementation:** `PhysicalFulfillment`, `FulfillmentEvent`; guarded transition API, item-level concurrency, event/transaction side effects.

### **F14 — Artist proceeds and payouts**

* **Purpose:** Attribute money to the correct Artist and pay only after the proper milestone.  
* **Rules:** §7.6–10,13; §14.1–8; §15.1–9; Add.2–3.  
* **Functional requirements:** Create separate proceeds obligations per Order Item or paid Quote, using snapshotted values. Digital proceeds \= price less fee; physical \= markup less fee; commission \= paid Quote price less fee. Digital becomes eligible after payment and entitlement, physical after delivery, Commission after COMPLETED. Track `HELD`, `ELIGIBLE`, `RESERVED`, `PAID`, `VOID` and payout `PENDING`, `PROCESSING`, `PAID`, `FAILED`; group one or many obligation lines. Prevent refund approval after corresponding proceeds are PAID in normal flow.  
* **Inputs → outputs:** Snapshot financials, eligibility event, payout instruction/provider result → obligation and payout records.  
* **Decision logic:** IF source milestone reached and obligation not void/refunded THEN mark eligible once; IF payout confirmed paid THEN mark associated obligations paid ELSE retain/release safely according to outcome.  
* **Validation:** One obligation per source, Artist ownership match, positive payout/line amounts, VND; lines sum to payout; no simultaneously active duplicate line for proceeds. Scheduling and failed-payout retry policy: **Needs Clarification**.  
* **Edge cases:** Refund approval vs payout reservation; provider duplicate success; partial paid Quote refund policy is full Commission only; zero-proceeds obligation and payout line (\>0 in schema).  
* **Acceptance criteria:** Given paid digital and undelivered physical items, when eligibility runs, then only digital proceeds are eligible. Given Commission completed with two paid Quotes, then each quote obligation becomes eligible without recalculating earlier terms.  
* **Implementation:** `ArtistProceeds`, `Payout`, `PayoutLine`; payout provider adapter, guarded status changes and finance reconciliation; transactionally coordinate refund and reservation.

### **F15 — Order item refunds and shipping refunds**

* **Purpose:** Manually decide whole-item refunds, including a one-time shipping refund when all physical items are refunded.  
* **Rules:** §16.1–13; §5.7; §6.4; §15.8–9; §31.12.  
* **Functional requirements:** Customer may request refund for an affected Order Item; Admin reviews and approves/rejects with history. No partial monetary refund within item, including physical quantity \>1. Direct digital after successful payment is nonrefundable; physical ordinary refund only before confirmed delivery and before its Artist proceeds have been paid. Refund full refundable item amount; when all physical Order Items are fully refunded, refund the one Order shipping charge exactly once. Keep purchase/payment records unchanged and track transfer status.  
* **Inputs → outputs:** Order Item, reason, Admin decision, provider refund result → request, item transfer, optional shipping refund and audit.  
* **Decision logic:** IF paid digital OR delivered physical OR corresponding proceeds PAID THEN reject ordinary approval; ELSE approve eligible whole item. IF all physical items fully refunded AND shipping not already refunded THEN create one shipping refund.  
* **Validation:** Requester is buyer; item belongs to paid Order; full item amount only; no duplicate open/completed refund; shipping amount no more than snapshotted charge. Exact tax refund allocation is **Needs Clarification**.  
* **Edge cases:** Two physical refunds approved concurrently; refund provider failure/retry; item delivered while decision pending; payout reserved while refund pending; shipping refund on failed transfer versus approval timing.  
* **Acceptance criteria:** Given two physical items and shipping 20, when the first is fully refunded, then shipping refund is zero; when the second is fully refunded, then exactly one 20 shipping refund is issued. Given a delivered item, when Admin attempts ordinary approval, then it is rejected.  
* **Implementation:** `RefundRequest`, `RefundTransfer`, `ShippingRefund`, `ArtistProceeds`, `AdminAudit`; provider refund API; serializable/locked approval and one-time shipping decision.

### **F16 — Commission requests and messages**

* **Purpose:** Permit eligible Customers and Artists to negotiate work with an immutable conversation.  
* **Rules:** §17.1–9; §18.1–8; §1.8,10; §2.16.  
* **Functional requirements:** Customer may request an ACTIVE Artist currently accepting requests, with description and optional reference files; self commissioning is forbidden. Artist may show informational starting price. Each Commission has a participant-only message thread; sent messages cannot be edited and cannot change terms or lifecycle. Suspension blocks new requests/acceptances but does not automatically terminate active work.  
* **Inputs → outputs:** Target Artist, requester, description, attachments/message → Commission `REQUESTED`, immutable messages.  
* **Decision logic:** IF Artist ACTIVE and accepting and not requester THEN create request ELSE reject. IF sender is participant THEN append message ELSE deny.  
* **Validation:** Nonempty description/message; valid file attachment; Artist/User IDs distinct; limits and allowed file types: **Needs Clarification**.  
* **Edge cases:** Availability toggles during request; suspension during ongoing messaging; deleted participant and evidence access; duplicate submit.  
* **Acceptance criteria:** Given an ACTIVE accepting Artist, when another User submits a description, then a `REQUESTED` Commission is created. Given an Artist attempting to commission themselves, then request fails. Given a sent message, edit fails.  
* **Implementation:** `Commission`, `CommissionAttachment`, `CommissionMessage`, `StoredFile`; request/message APIs and participant checks.

### **F17 — Commission quotes and scope changes**

* **Purpose:** Keep formal scope/price agreements separate from chat and preserve each paid change.  
* **Rules:** §19.1–13; §20.1–5; §21.1–10; §14.7; Add.1–2.  
* **Functional requirements:** Only commissioned Artist may issue Quote with price, scope/deliverable and completion terms; Customer may accept/decline, Artist may withdraw unaccepted offer. Accepted Quotes are immutable. Initial paid Quote requires full confirmed payment before `IN_PROGRESS`. Later accepted Quotes add formal scope without rewriting earlier Quotes; paid additional scope binds only after payment. A zero-price Quote binds on acceptance without payment. Pending later Quote does not erase prior paid scope; decline leaves scope unchanged. There is no fixed revision allowance or required quote expiry.  
* **Inputs → outputs:** Quote fields, accept/decline/withdraw action, provider confirmation → Quote history, financial snapshot, Commission state.  
* **Decision logic:** IF price=0 and Customer accepts THEN bind scope with no Payment; ELSE IF price\>0 THEN await full success before binding paid scope. IF initial paid Quote succeeds THEN enter `IN_PROGRESS`; IF later Quote is pending THEN continue already paid work.  
* **Validation:** Price ≥0 VND; nonempty scope and completion terms; only commissioned Artist issues; acceptance only by Customer; accepted terms immutable; sequence unique. Concurrent outstanding Quote policy: **Needs Clarification**.  
* **Edge cases:** Accept vs withdraw race; acceptance after suspension; several accepted changes awaiting payment; zero-price initial Quote vs requirement for initial paid Quote (see D).  
* **Acceptance criteria:** Given a paid initial Quote, when a new 0 VND change is accepted, then no payment is created and earlier financial snapshots remain unchanged. Given an unpaid initial Quote, when payment attempt fails, then Commission does not enter `IN_PROGRESS`.  
* **Implementation:** `CommissionQuote`, `Payment`, `CommissionEvent`, `PlatformFeeRate`; formal action endpoints; conditional status updates and immutable-term trigger.

### **F18 — Commission delivery, review and revisions**

* **Purpose:** Preserve submitted work versions and give Customer a formal approval/revision loop.  
* **Rules:** §22.1–13; §23; §31.4–7.  
* **Functional requirements:** Only commissioned Artist may submit one or more immutable files as a version when Commission is eligible for submission. Submission enters `SUBMITTED_FOR_REVIEW`. Customer may approve current submission, making Commission `COMPLETED`, or submit a formal revision request from review state, returning work for a later version. No numeric revision cap; retain versions and requests.  
* **Inputs → outputs:** File IDs/notes, review approval or revision details → deliverable version, event, state change.  
* **Decision logic:** IF review state and Customer approves latest submission THEN complete once; ELSE IF review state and Customer requests revision THEN record request and return to work; ELSE reject.  
* **Validation:** At least one file per submission; unique version number; Customer/Artist roles; revision details required; immutable version/file association.  
* **Edge cases:** Approval vs revision race; stale version approved; pending cancellation/dispute/quote conflicts; no completion deadline enforcement specified.  
* **Acceptance criteria:** Given `IN_PROGRESS`, when Artist submits version 1, then Customer review begins. Given review, when Customer requests changes, then request and version 1 remain and Artist can submit version 2\. Given approval of current version, then Commission completes once.  
* **Implementation:** `CommissionDeliverable`, `DeliverableFile`, `RevisionRequest`, `CommissionEvent`; guarded transition API with row version.

### **F19 — Commission cancellation and dispute resolution**

* **Purpose:** Resolve formal cancellation requests and Admin reviewed conflicts without losing evidence.  
* **Rules:** §23–24; §26.1–9; §18.7–8; §31.8–9; §29.  
* **Functional requirements:** Either participant may request cancellation, the other accept/reject, and requester withdraw while pending; agreed cancellation changes Commission to `CANCELLED` without automatically refunding. Admin may force cancellation through dispute handling. Either participant may open at most one active dispute; Admin changes dispute `OPEN`→`UNDER_REVIEW`→`RESOLVED` with continuation, cancellation with refund, or cancellation without refund, and retains evidence/history. Messages remain possible while formal actions are pending.  
* **Inputs → outputs:** Request/reason, response, dispute evidence, Admin outcome → formal action records, Commission transition/audit.  
* **Decision logic:** IF cancellation pending and other party accepts THEN cancel; IF rejects/withdraws THEN retain Commission state. IF dispute outcome calls for refund THEN use approved refund workflow subject to payout restrictions; conflicts requiring an exception are **Needs Clarification**.  
* **Validation:** One pending cancellation and one active dispute per Commission; actor must be proper participant/Admin; reason required; resolved actions cannot process twice.  
* **Edge cases:** Cancellation acceptance vs completion race; two disputes opened concurrently; dispute outcome `CANCEL_REFUND` after proceeds paid; cancellation while additional Quote payment pending.  
* **Acceptance criteria:** Given pending cancellation, when requester withdraws before response, then Commission is not cancelled and request remains `WITHDRAWN`. Given an active dispute, when another is opened concurrently, then at most one succeeds.  
* **Implementation:** `CancellationRequest`, `CommissionDispute`, `CommissionEvent`, `AdminAudit`; state transition service and unique filtered indexes; outcome plus refund coordination.

### **F20 — Commission refunds**

* **Purpose:** Refund the entire paid Commission after cancellation when manually approved.  
* **Rules:** §24.11–13; §25.1–8; §15.8–9; §31.12.  
* **Functional requirements:** After paid Commission cancellation, Customer may request refund. Admin reviews and approves/rejects. Approval covers full refundable amounts across all accepted paid Quotes, including later paid changes, without changing Quotes or payment history. No partial Commission refund; no normal approval once corresponding proceeds paid. Track each payment's external refund separately and retain history.  
* **Inputs → outputs:** Cancelled Commission, reason, decision, provider results → request and refund transfers across paid Quotes.  
* **Decision logic:** IF Commission cancelled, has successful paid Quotes and no corresponding proceeds PAID THEN approve full sum and initiate refunds for every relevant payment ELSE reject ordinary approval.  
* **Validation:** Requester is Commission Customer; exactly full successful paid Quote total; no duplicate open/completed refund; transfers map to original successful payments.  
* **Edge cases:** One of several provider transfers fails; additional Quote pending at cancellation; concurrent payout; dispute orders refund despite paid proceeds. Rollback/compensation policy for partial external success: **Needs Clarification**.  
* **Acceptance criteria:** Given paid Quotes of 100 and 30, when full refund approved after cancellation, then total refund is 130 across original payments, with immutable Quotes retained. Given any corresponding proceeds already PAID, then ordinary approval fails.  
* **Implementation:** `RefundRequest`, `RefundTransfer`, `Payment`, `CommissionQuote`, `ArtistProceeds`, audit; multi-transfer orchestration with idempotency.

### **F21 — Admin audit and historical retention**

* **Purpose:** Make sensitive decisions traceable and preserve commercial meaning over time.  
* **Rules:** §1.14–17; §2.7,11–12,17; §3.15–16; §10; §27–31; Add.6.  
* **Functional requirements:** Record staff actor, action, target, time and reason/context for suspension/reinstatement, appeals, moderation, refund decisions, dispute resolution, forced cancellation and exceptional financial actions. Retain Orders, Commissions, payments, payouts, refunds, accepted Quotes, messages, purchased assets and submitted deliverable versions despite account/catalog status changes. Allow deletion of temporary unreferenced files only. Preserve historical financial and lifecycle events.  
* **Inputs → outputs:** Authorized Admin command and reason, ordinary lifecycle transition → audit/event record and retained business history.  
* **Decision logic:** IF action is sensitive THEN commit audit with action or fail both; IF record has historical business references THEN archive/anonymize current data without destroying the historical record.  
* **Validation:** Staff ID, action, target and timestamp required; reasons where applicable; immutable transaction facts and restricted UPDATE/DELETE permissions.  
* **Edge cases:** Audit write failure; Admin account deactivation; privacy erasure request versus required historical data; orphan cleanup racing new reference.  
* **Acceptance criteria:** Given Admin approves a refund, when decision commits, then corresponding audit identifies Admin, target, timestamp and context. Given Listing archived after purchase, then Order snapshots and purchased files remain identifiable.  
* **Implementation:** `AdminAudit`, `CommissionEvent`, `FulfillmentEvent`, immutable triggers and permissions; transactional audit writes; retention and object-storage cleanup job.

## **C. Cross-feature rules**

1. **Identity and access:** A User may be Customer and Artist; Artist ownership is checked through ArtistProfile.UserId. Staff credentials are separate. Check ownership/participation at every operation, including file access (F01–F08, F16–F21).  
2. **Availability is evaluated at Order creation/request creation:** Listing, Artwork, Artist, cost-review flag and supported province are checked together. An Order already created remains payable until its expiration despite later unlisting; suspension's effect on already pending Commissions needs clarification (F03, F05–F11).  
3. **Snapshot boundary:** Cart prices are live; Order creation fixes item, file, fee, tax, shipping, address and identity snapshots. Each accepted Quote fixes its terms/financials. Subsequent edits cannot rewrite these facts (F07, F09–F10, F17, F21).  
4. **One full payment per payable:** Payment belongs to an Order or Quote, never both. Multiple attempts are allowed; confirmed success and side effects happen once. Zero-price Quotes bypass Payment (F11, F17).  
5. **Milestone differences:** Direct digital entitlement and proceeds follow payment; physical entitlement and proceeds follow delivery; Commission proceeds follow completion. These milestones also bound ordinary refunds (F11–F15, F18, F20).  
6. **Money and attribution:** VND only; decimal money and fee rates as specified. Artist fee bases differ by item type; shipping and manufacturing cost are not Artist income. Tax and shipping are distinct, historical amounts. One mixed Order is attributed at Order Item level (F07, F10, F14–F15).  
7. **Concurrency:** Protect price/availability snapshots, payment notification deduplication, entitlement grants, Commission transitions, one active dispute/cancellation, payout reservation and refund decisions with transactions and database constraints, not read-then-write checks alone (all commerce features).  
8. **Retention:** Status changes and account deletion do not cascade into historical transactions or referenced objects. Admin decisions and lifecycle evidence remain inspectable (F01, F03, F05–F08, F21).

## **D. Missing / ambiguous requirements and schema alignment**

These are questions to answer before implementing the affected path; they are not assumed defaults.

| ID | Needs Clarification | Question / impact | Schema observation |
| ----- | ----- | ----- | ----- |
| Q01 | Zero-price Order | Can a cart of zero-priced digital/physical Listings produce an Order? If so, what replaces full provider payment as the fulfillment trigger? | `Payment.Amount > 0`, while `Listing.Price >= 0` and `Order.TotalAmount` can be zero. |
| Q02 | Initial zero-price Quote | Can the initial Commission Quote be 0 VND? If yes, does acceptance start `IN_PROGRESS` without payment? | Rules explicitly allow zero-price scope changes, but initial scope is described as an initial accepted **paid** Quote. `CommissionQuote` keeps zero-price acceptance as `ACCEPTED`. |
| Q03 | Quote overlap | May multiple Quotes be `OFFERED`/`ACCEPTED` and unpaid at once, and how does a new offer supersede an old one? Which actions are blocked while one is pending? | No unique active-quote constraint; Quote statuses omit explicit superseded state. |
| Q04 | Commission transition table | Which exact transitions and actors are allowed for decline, revision, cancellation, dispute, and pending additional payment? Is `DISPUTED` an overriding state or a separate dispute flag? | `Commission.Status` includes `DISPUTED`; `CommissionDispute` independently tracks status. |
| Q05 | Suspension and pending work | What happens to a request or Quote already pending when the Artist is suspended? May they issue/accept a Quote, or only continue an already `IN_PROGRESS` job? | Rules expressly allow ongoing `IN_PROGRESS` work, but do not settle other stages. |
| Q06 | Fulfillment source and transitions | Who confirms shipping/delivery, with what evidence, and which transitions/reversals are allowed? Who may mark cancelled/failed? | `PhysicalFulfillment` has states and event table but no transition policy. |
| Q07 | Refund amount and tax | Does an item refund include its item-level tax, and how is Order-level tax allocated? When is shipping considered refunded: approval or successful provider transfer? | `OrderItem.TaxAmount`, `MarketplaceOrder.TaxAmount`, `ShippingRefund`, `RefundTransfer` exist; no sum/transfer policy enforced. |
| Q08 | Shipping quote | How is the shipping charge calculated, and may checkout proceed when no quote is available? | Only final Order-level `ShippingAmount` is modeled. |
| Q09 | Tax input | Who/what supplies the tax amount while tax determination is out of scope? How is Order tax reconciled against item taxes? | Both Order and item have tax fields; cross-table sum must be enforced by transaction contract. |
| Q10 | Fee rounding and effective rates | At what time is fee rate selected for an Order/Quote, and what rounding rule applies to per-unit vs line totals? | `PlatformFeeRate.EffectiveFrom`; snapshots store four-decimal money/six-decimal rates. |
| Q11 | Refund vs payout reservation | Must a pending refund freeze payout, and can an approved refund proceed when proceeds are `RESERVED` but not `PAID`? | Both workflows have intermediate states; no automatic exclusion constraint. |
| Q12 | Dispute refund conflict | How does `CANCEL_REFUND` resolve if some proceeds already paid, since normal refunds forbid that? Is the outcome blocked or handled as exceptional Admin correction? | Dispute outcomes permit it; normal refund rules do not. |
| Q13 | External transfer failure | For multi-payment Commission refund, what is the policy if only some transfers succeed? What are retry and reconciliation rules for refunds/payouts? | Transfers/statuses exist, but no orchestration contract. |
| Q14 | Payment anomalies | Which provider(s), signature rules, timeouts, and reconciliation procedure handle late, unmatched, wrong-amount or duplicate successes? | `ProviderEvent`/`PaymentAttempt` support recording but no provider policy. |
| Q15 | Catalog publication and moderation | Which exact Artwork/Listing statuses are publicly visible and purchasable? Can suspended Artists edit drafts? What preview media is mandatory? | Status columns alone do not define public visibility. |
| Q16 | Account/privacy lifecycle | What authentication, password, verification, account blocking, deletion confirmation and historical access policies apply? Can a deleted buyer still recover an entitlement? | User state and hashes exist; detailed identity lifecycle is unspecified. |
| Q17 | Limits and uploads | What are attachment/media size, type and count limits; and how is a missing/corrupt storage object handled? | File metadata exists; no product limits. |
| Q18 | Idempotent user commands | Which client operations require idempotency keys (checkout, requests, payout submissions), and how long are keys retained? | Provider events are keyed, but client-request deduplication is not modeled. |
| Q19 | Payout cadence and minimums | Who initiates payouts, on what schedule/threshold, and what happens to zero proceeds or failed transfers? | `PayoutLine.Amount > 0`; `ArtistProceeds.Amount >= 0`. |

**Schema implementation checks, not new business rules:** The SQL explicitly notes that cross-table sums, transition guards and immutability need a separate transaction contract (`Marketplace_Schema_Notes.md`, not attached). Implement them in services/database transactions. In particular, enforce Order total \= sum of snapshotted lines \+ shipping \+ tax; exact asset set at Order creation; access-class consistency; Artist ownership; one or more items/assets; fee calculations; refund sum and source-payment relation; payout line totals and Artist identity. The current triggers protect several snapshots but cannot alone establish these aggregate facts. Avoid interpreting the presence of `MODERATOR` in the schema as an active Moderator workflow: the rules assign current moderation to the Admin.

## **E. Suggested implementation order**

| Phase | Features | What this delivers |
| ----- | ----- | ----- |
| 1\. Shared foundation | **F01** Customer identity, **F02** staff and Artist authorization, **F04** addresses and delivery areas, **F08** protected files, **F21** audit and retention foundations | Accounts, permissions, storage, and historical safeguards |
| 2\. Catalog and pricing | **F05** Artwork and tags, **F06** Listings, **F07** manufacturing costs and platform fees, **F03** Artist availability and suspension | Purchasable digital and physical offerings |
| 3\. Standard checkout | **F09** Cart, **F10** Order creation and snapshots, **F11** payment processing for **Orders** | Customers can place and pay for Orders |
| 4\. Standard Order completion | **F12** digital entitlements, **F13** physical fulfillment, **F14** proceeds and payouts for **Order Items**, **F15** Order Item refunds | The complete standard Order workflow |
| 5\. Commissionfoundation   | **F16** requests and messages, **F17** Quotes and scope changes; extend **F11** to paid Quotes | Customers and Artists can agree on and pay for custom work |
| 6\. Commission completion | **F18** deliverables and revisions, **F19** cancellations and disputes, **F20** Commission refunds; extend **F14** to paid Quotes | The complete Commission workflow |

