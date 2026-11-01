# Commission delivery, review and revisions (F18)

Decisions recorded 2 November 2026 for the first F18 increment: the **delivery loop**. The commissioned Artist submits
versions of the work, and the Customer approves the latest one or asks for a revision. Approval makes the final 50%
payment due. Paying it, unlocking the files and completing the Commission are F18b:
[Commission final payment and completion](commission-completion.md).

## Approved policies

- **Scope (B1):** the delivery loop only. Submissions, review, approval and revision requests. No money moves, and no
  Commission reaches `COMPLETED` in this increment, so the spec's "completes once" criterion moves to F18b.
- **No work stages (B2, rule change):** the system does not record sketch, lineart, colouring or render. 3D work does
  not follow those 2D stages, and each team member may have their own refund policy. This replaces amendment §D.13's
  tracked stages and §D.15's fixed refund table: a cancellation refund is what the Customer and the Artist agree,
  reviewed by the Admin and never more than the held first payment. How the Admin checks the agreed amount is settled
  with F20. See [Known limits](#known-limits) for the weakness.
- **Approval state (B3):** approval returns the Commission to `AWAITING_PAYMENT`. No migration. The first installment is
  already `SUCCEEDED`, so it is never paid again: a new attempt pays the final installment
  ([F18b](commission-completion.md)), and a late success for the first is recorded as `ALREADY_PAID` with the status
  unchanged ([Commission Quote payments](commission-quote-payments.md)). The approval time is the
  `DELIVERABLE_APPROVED` event's `OccurredAt`; the final payment has no deadline (F18b B1).
- **No preview yet (B4):** the review preview format is undecided (amendment §E). Both participants see each version's
  notes and file names and sizes, but only the Artist can download the files. The Customer gets
  `403 DELIVERABLE_LOCKED` until the final payment, which unlocks the approved version
  ([F18b](commission-completion.md)), and reviews through images shared in the message thread.
  This does not yet meet §D.13's protected preview.
- **Immutable versions (B5, migration `0006_deliverable_immutable.sql`):** triggers reject `UPDATE` and `DELETE` on
  `CommissionDeliverable`, `DeliverableFile` and `RevisionRequest` (`THROW` 51012–51014), as `TR_Message_Immutable`
  does for messages (§22.3, §22.10, §30). A new submission is a new version.
- **Submitting (R4, §22.1–4):** only the commissioned Artist, while the Commission is `IN_PROGRESS` or
  `REVISION_REQUESTED`. Each version has 1–10 files, with the F16 types (png, jpg, jpeg, webp, pdf, psd, zip) and the
  20 MB limit, plus optional notes of up to 5000 characters. The files are stored as protected `COMMISSION` files and
  checked before anything is locked or written. `VersionNo` = highest + 1, taken under the Commission lock
  (`UQ CommissionId, VersionNo`). The Commission becomes `SUBMITTED_FOR_REVIEW`.
- **File access (R5):** participants can list versions; anyone else gets `404 COMMISSION_NOT_FOUND`. Only the Artist
  can download a version's files.
- **Approving (R6, §22.6):** only the Customer, only while `SUBMITTED_FOR_REVIEW`, and only the latest version. A stale
  version gets `409 DELIVERABLE_NOT_CURRENT`, a second approval `409 COMMISSION_ACTION_NOT_ALLOWED`. No Payment is
  created.
- **Revision requests (R7, §22.7–8):** only the Customer, only while `SUBMITTED_FOR_REVIEW`, on the latest version, with
  details of 1–5000 characters. It writes a `RevisionRequest` against that version, and the Commission becomes
  `REVISION_REQUESTED`. There is no revision allowance; the reviewed version stays as it was.
- **Deactivated Artist (R8, amendment §A.4):** keeps submitting versions of work already paid for.
- **Concurrency:** every action locks the Commission row (`UPDLOCK`) first, as Quote actions do. A racing approval and
  revision request have exactly one winner (§31.4, §31.9); the loser gets `409 COMMISSION_ACTION_NOT_ALLOWED`.
- **History (§23):** each action writes a `CommissionEvent` in the same transaction: `DELIVERABLE_SUBMITTED`
  (`RelatedId` = the version), `DELIVERABLE_APPROVED` (the version) and `REVISION_REQUESTED` (the revision request).

## HTTP contract

All endpoints need a Customer bearer token.

| Endpoint | Who | Request | Success |
| --- | --- | --- | --- |
| `GET /commissions/{id}/deliverables` | participants | — | 200, versions in order, each with `files` and `revisionRequests` |
| `POST /commissions/{id}/deliverables` | the Artist | multipart: optional part `request` (JSON `notes`), 1–10 parts `files` | 201 with the version |
| `POST /commissions/{id}/deliverables/{deliverableId}/approve` | the Customer | — | 200 with the Commission summary (`AWAITING_PAYMENT`) |
| `POST /commissions/{id}/deliverables/{deliverableId}/revision-requests` | the Customer | JSON `details` | 201 with the revision request |
| `GET /commissions/{id}/deliverables/{deliverableId}/files/{fileId}/content` | the Artist | — | 200, the file streamed |

A version carries `deliverableId`, `commissionId`, `versionNo`, `notes`, `submittedAt`, `files` (`fileId`,
`originalName`, `contentType`, `byteLength`) and `revisionRequests` (`revisionId`, `deliverableId`,
`requestedByUserId`, `details`, `requestedAt`).

New error codes: `DELIVERABLE_NOT_FOUND`, `DELIVERABLE_FILE_NOT_FOUND` (404); `DELIVERABLE_NOT_CURRENT` (409);
`DELIVERABLE_LOCKED` (403). Wrong participant: `403 ACCESS_DENIED`; wrong status: `409 COMMISSION_ACTION_NOT_ALLOWED`
or `409 COMMISSION_CLOSED`; invalid notes, details or no files: `400 COMMISSION_INVALID`; the file errors of F16.

## Financial correctness

F18 moves and records no money. It touches money in one place: reusing `AWAITING_PAYMENT` after approval. The F17b
guards keep the first installment from being paid twice (`payment_attempt_after_approval_never_repays_first_ok`,
`first_payment_success_after_approval_ko`). The held first payment stays `HELD` until the final payment completes the
Commission ([F18b](commission-completion.md)).

## Deferred

- **F18b** (done, [Commission final payment and completion](commission-completion.md)): the final payment, the
  Quote's `PAID` status, `COMPLETED`, unlocking the approved version and releasing Quote proceeds. The 7-day close
  (§D.14) was removed instead of built.
- **The review preview** (format undecided, amendment §E).
- **Change Quotes, cancellation, disputes and refunds** (F17 onwards, F19, F20).

## Known limits

- **No record of work progress.** Without stages, a Customer who disputes an agreed refund has only the message
  thread and the submitted versions as evidence, and the Admin has no fixed rule to check an agreed amount against.
  Closing it: a refund policy stored with each Quote (per Artist), or a progress record the Customer can see.
- **`AWAITING_PAYMENT` means two things:** the first payment is due (installment 1 `PENDING` or absent) or the final
  one (installment 1 `SUCCEEDED`). Clients must read the Quote's payments to tell them apart. Closing it: a separate
  status, which needs a migration.
- **No protected preview.** The Customer approves without seeing the files in the system. Closing it: a preview file
  per version, which needs a decided format and a schema change.
- **An approved Commission can wait for its final payment forever** (no deadline, F18b B1).
- **Uploads are read fully into memory,** as for Commission attachments.

## Tests

`CommissionDeliveryServiceTest`, `CommissionDeliveryControllerTest` and the approval cases in
`CommissionPaymentServiceTest` are unit tests. `CommissionDeliveryPersistenceTest` runs only against the disposable
test database selected by `TEST_DB_URL` and leaves its rows there, because versions can never be deleted. It moves its
Commissions to `IN_PROGRESS` directly, writing no Payment, proceeds or ledger row, so the database stays reconcilable.
