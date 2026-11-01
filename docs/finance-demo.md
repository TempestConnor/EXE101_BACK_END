# Finance demo script

How to show, in the class demo, that money moves through the system correctly and that the finance side is protected.
The rules behind it are [amendment F](Marketplace_Business_Rules_Final.md#amendment-f--money-handling-1-november-2026)
of the business rules; the design is in [Artist proceeds, releases and the ledger](artist-proceeds.md).

## Before the demo

1. **Exercise the VNPay sandbox once for real.** The integration has not yet been run against the sandbox
   ([Payment](payment.md#known-limits)). Put the sandbox terminal code and hash secret in
   `.local/application.properties`, expose the IPN URL through a public tunnel (for example ngrok), and register
   that URL in the VNPay sandbox portal.
2. **Start from a clean test database:** `sqlcmd -S <server> -U <user> -C -i database/test/reset_test_database.sql`.
3. **Run the full test suite** with `DB_URL` and `TEST_DB_URL` pointing at `CuratedArtworkMarketplace_Test`, and keep
   the result. The financial tests below are the evidence for scenarios that cannot be shown live.

## Part 1: money moving

| Step | Show |
| --- | --- |
| A Customer checks out one digital item and one print from two Artists | `POST /orders`: one Order, two items, each with its own Artist |
| They pay on VNPay's sandbox page with VNPay's test card | VNPay calls the IPN; the Order becomes `PAID` |
| The digital file downloads; the print does not | `GET /purchases`; the print's files wait for its return window |
| The books | `GET /admin/finance/reconciliation`: `CASH` = Order total, each Artist's share in `ARTIST_HELD`/`ARTIST_RELEASING`, the print's manufacturing cost in `MANUFACTURER_PAYABLE`, `clean: true` |
| The Admin delivers the print; 48 hours later the window closes | The print's files are granted and its release is created |
| The Admin records each release as `PAID` with its transfer reference | Proceeds `PAID`; `ARTIST_RELEASING` back to 0; `CASH` down by the amounts released; an audit row per action |

## Part 2: the finance side is protected

| Attack or failure | What happens | Evidence |
| --- | --- | --- |
| Change the amount in a VNPay notification and resend it | Rejected: the signature fails (`97`); nothing is recorded | `PaymentServiceTest` |
| Send the same success notification twice | One payment, one entitlement, one proceeds record, one ledger receipt | `duplicate_ipn_records_payment_once_ok` |
| Two attempts of one Order succeed at the same moment | One pays; the other is an exception booked to `UNAPPLIED` | `concurrent_ipn_records_payment_once_ok` |
| Pay the wrong amount, or after the Order expired | Nothing granted; the money received is booked to `UNAPPLIED` | `exception_payment_records_unapplied_ok` |
| The database fails halfway through a payment | Everything rolls back; VNPay's retry is handled cleanly | `ledger_failure_rolls_back_payment_ko` |
| Edit or delete a ledger line in SQL | Refused by the database trigger | `ledger_entries_immutable_ko` |
| Record the same money event twice | Refused by the database unique key | `duplicate_event_rejected_by_unique_key_ko` |
| A print delivered less than 48 hours ago | Its money stays held | `physical_proceeds_held_before_window_closes_ko` |
| Two Admins mark the same release `PAID` at once | Settled once; the second gets `409` | `concurrent_outcome_recording_settles_once_ok` |
| The books disagree with the records | Reconciliation lists the mismatch, and releases are blocked (`409 RECONCILIATION_MISMATCH`) | `reconciliation_detects_*` tests |
| A whole Order from payment to paid releases | Every check clean; Artist balances 0; cash = total − releases | `full_flow_reconciles_ok` |

## Declared limitations

State these openly (amendment F.10). Each is a weakness, with what closing it would take:

- No real money moves: VNPay's sandbox uses test money. Closing it needs a registered business with a production
  VNPay contract.
- Artist releases are transferred by hand and recorded, because no payout provider available to individuals offers
  a payout API with a sandbox.
- `CASH` is not compared with a bank statement, because the sandbox has no balance.
- A second Admin does not yet confirm manual money actions (F.8), and there are no release limits or pause switch
  yet (F.9).
