# Financial correctness

Treat changes affecting prices, checkout totals, payments, refunds, Commissions,
Artist proceeds, payouts, and payment-triggered entitlements as high-risk changes.
This includes shared code that affects these paths. The controlled class/demo
scope does not waive these requirements.

Follow [repository guidance](repository-guidance.md) for requirements, database
constraints, conflicts, and validation reporting, and [testing conventions](testing.md)
for general test structure. [Implementation scope](implementation-scope.md) still
controls release priorities and deferrals. This policy does not activate deferred
features or authorize unrelated schema changes.

## Before implementing an affected path

- Identify the applicable business rules, financial invariants, permitted state
  transitions, and failure scenarios.
- Resolve missing decisions about rounding, currency, refund eligibility, payment
  anomalies, and refund/payout coordination with the user. Do not invent financial
  policy. Continue independent work where possible.

## Implementation requirements

- Preserve `BigDecimal` money/rate values and database precision. Use explicit,
  agreed currency, scale, and rounding rules.
- Calculate and validate amounts on the server using trusted data and immutable
  order snapshots. Do not trust client-supplied totals or client-reported payment
  success.
- Authenticate provider callbacks and verify their association with the expected
  payment, amount, and currency.
- Make payment, refund, and payout processing safe against duplicate requests,
  retries, repeated callbacks, and concurrent execution.
- Enforce financial invariants under concurrency, including preventing duplicate
  financial effects and refunds exceeding the eligible amount.
- Account for partial failure between the payment provider and database. A
  database transaction cannot roll back an external payment. Do not assume a
  timeout means a payment or refund failed; provide a defined recovery or
  reconciliation path before retrying.
- Preserve required financial history, snapshots, and persistent audit records.
  Do not silently overwrite or delete financial evidence. Commit required Admin
  audit records in the same database transaction as the associated local action,
  as specified in implementation scope.

## Required verification

- Add automated tests for the affected business rules and boundaries, including
  exact monetary results and resulting persisted state.
- Cover applicable rejection and failure paths: unauthorized actions, invalid
  amounts/currencies, rounding boundaries, duplicate requests, repeated or
  out-of-order callbacks, timeouts, partial failures, concurrent operations, and
  refund/payout races.
- Verify affected database constraints, rollback behavior, and concurrency with
  integration tests against an isolated SQL Server test database. Mock-only tests
  do not prove these properties. These tests verify application financial
  invariants, not Spring Data's generic CRUD implementation.
- Verify provider integration using its sandbox/test mode when applicable. Never
  use live charges, refunds, payouts, or production data for tests.
- Add a regression test for every financial correctness bug fixed.

## Completion criteria

- Run the relevant tests and report commands, outcomes, skipped checks, and
  remaining risks.
- Do not describe the affected feature as complete or verified while required
  checks are failing, skipped, or unavailable. Report the specific validation
  gap and what is needed to resolve it.
- Do not weaken tests or bypass integrity checks to obtain a passing build.
- Select the actual affected tests when validating. The current
  `scripts/dev.ps1 Verify` command selects only `DatabaseMappingTest`; it does
  not run a financial test suite. Mapping validation or a successful package
  build alone does not establish financial correctness. Consult repository
  guidance for current commands and their limitations.
