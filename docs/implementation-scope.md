# Implementation scope and priorities

Decision recorded: 27 September 2026.

F01 increment: Customer registration and bearer-token login use the approved
password, verification, and blocking policies recorded in
[Customer authentication](customer-authentication.md). Account deletion remains
deferred. F02 staff authentication and Artist authorization is the next dependency.

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

## Priority 2: Basic Artist suspension and reinstatement

After the standard Order workflow, implement the reduced F03 workflow:

- Admin-only indefinite suspension and manual reinstatement.
- Reasons, staff identity, timestamps, and retained suspension history.
- Transactional audit of status changes.
- New-purchase restrictions enforced through Artist availability rather than
  rewriting every Listing.
- Preservation of the Artist's Customer capabilities and existing commercial
  records and commitments.

This is planned secondary work, subject to available project time. It is not a
prerequisite for completing the standard-order demo milestone, apart from the
availability checks retained in priority 1.

Timed suspension, automatic expiry/extension, and the appeal workflow are deferred.
For the controlled demo, an appeal may be discussed with the project operator
and followed by audited reinstatement. This temporary process does not satisfy
the full specification's retained appeal-submission/decision workflow.

## Priority 3: Commissions

F16–F20 and the Commission-specific extensions to F03, F08, F11, F14 and F21 are
tertiary work. Start this journey after standard orders and basic suspension,
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
| Artist appeals (part of F03) | Defer submission, decisions, and appeal-history workflow; basic suspension/reinstatement is sufficient for the controlled demo. | After basic suspension, when formal moderation review becomes a project priority. |
| Timed suspension (part of F03) | Defer expiry scheduling and extension handling; use indefinite suspension and manual reinstatement initially. | After basic suspension, when automatic expiry is needed. |
| Dedicated Admin audit tools | Defer audit browsing dashboards, search/filter interfaces, reporting, exports, and routine manual review tooling. Keep automatic persistent audit recording with each implemented sensitive action. | After standard orders, when an operational audit-review need is identified. |

Deferring account deletion does not defer stable User IDs, retained transaction
links, order snapshots, or purchased assets. Keep existing account lifecycle
fields and do not use destructive database deletion as a substitute. Q16 remains
unresolved, including access to historical entitlements after deletion. The
deletion workflow must be defined before production, as required by F01.

No other feature is automatically deferred by this document.
