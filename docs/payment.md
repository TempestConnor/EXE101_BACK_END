# Provider payment and reconciliation (F11)

Decisions recorded 1 November 2026 for the F11 increment. Only Order payments; paid Quotes belong to priority 3.

## Approved policies

- **Provider (B1, Q14):** VNPay, payment API 2.1.0. The buyer starts an attempt and is sent to the signed VNPay
  payment page; only VNPay's server-to-server notification (IPN) confirms a payment. The page the buyer returns to
  (`returnUrl`) is never trusted. Notifications are authenticated with HMAC-SHA512 (`vnp_SecureHash`) over the
  `vnp_` fields sorted by name, values URL-encoded, with the merchant's hash secret, compared in constant time; a
  notification for another terminal code (`vnp_TmnCode`) is rejected the same way. Amounts travel as VND x 100 and
  must equal the Payment amount exactly. VNPay carries no currency in the IPN; VND is fixed by the signed page URL.
- **Anomalies (B2, §12.12):** an Order is late when the success notification is **received** after its `ExpiresAt`,
  whether or not the expiry job has run. Exceptions are recorded in the existing statuses only, with the reason in
  the application log; no schema change:

  | Case | Attempt | Payment | Order | Event | VNPay reply |
  | --- | --- | --- | --- | --- | --- |
  | Success, exact amount, Order payable | `SUCCEEDED` | `SUCCEEDED` | `PAID` | `PROCESSED` | `00` |
  | Failure (any non-`00` result) | `FAILED` | unchanged (`PENDING`) | unchanged | `PROCESSED` | `00` |
  | Success, wrong amount | `EXCEPTION` | unchanged | unchanged | `EXCEPTION` | `04` |
  | Success after `ExpiresAt`, or Order expired | `EXCEPTION` | `EXCEPTION` | unchanged | `EXCEPTION` | `02` |
  | Success when another attempt already paid | `EXCEPTION` | unchanged (`SUCCEEDED`) | unchanged | `EXCEPTION` | `02` |
  | Success for an unknown attempt | — | — | — | `EXCEPTION` | `01` |
  | Failure for an unknown attempt | — | — | — | `IGNORED` | `01` |
  | Notification for an attempt already settled | unchanged | unchanged | unchanged | `IGNORED` | `02` |
  | Same notification again (duplicate) | unchanged | unchanged | unchanged | earlier outcome kept | `02` |
  | Bad signature or malformed | nothing recorded | | | | `97` |

  An exception never fulfils, grants or pays out. Refunding money behind an exception is handled outside the
  system and is not recorded, also after F15 ([Refunds](refunds.md#known-limits)); the `EXCEPTION` statuses are the
  reconciliation list.
- **License contract (B3):** not part of F11. Demo digital purchases carry no contract consent; revisit before
  production, with its own schema decision (amendment §D.17, §E).
- **Endpoints (R1):** `POST /orders/{orderId}/payment-attempts` for the buyer only (staff `403`, another Customer's
  Order `404 ORDER_NOT_FOUND`); `GET /payments/vnpay/ipn` is public and authenticated by its signature. Payment
  status is read through `GET /orders/{orderId}`.
- **Starting an attempt (R2):** allowed only while the Order is `PENDING_PAYMENT`, `ExpiresAt` is in the future and
  its Payment is `PENDING`; otherwise `409 ORDER_NOT_PAYABLE`. No idempotency key: each call is a new attempt
  (§12.4) with its own 32-character `vnp_TxnRef`, and the VNPay page expires at the Order's `ExpiresAt`.
- **Failed attempts (R3):** the attempt becomes `FAILED`; the Payment stays `PENDING` and the buyer may try again
  until expiry. `Payment.FAILED` is unused.
- **Deduplication and locking (R4):** one transaction per notification. The `ProviderEvent` key
  (`VNPAY`, `vnp_TxnRef:vnp_TransactionNo:vnp_ResponseCode:vnp_TransactionStatus`) is read with
  `UPDLOCK, HOLDLOCK`, so a concurrent copy waits and then finds it; then the attempt, then the Order, then the
  Payment are locked — the Order before the Payment, as the expiry job does. The event row is written last, so a
  failure leaves nothing recorded and VNPay's retry is handled afresh. `UQ(ProviderName, ExternalEventId)` and
  `UX_Attempt_OneSuccess` remain the database guards.
- **Later side effects (R5):** no "order paid" hook. F12 (entitlements, [done](digital-entitlements.md)) and F14
  (proceeds and digital releases, [done](artist-proceeds.md)) add their calls to the same success transaction.
- **Unknown failures (R6):** recorded as `IGNORED`.

## Configuration

| Property | Environment variable | Default |
| --- | --- | --- |
| `app.payments.vnpay.tmn-code` | `VNPAY_TMN_CODE` | required |
| `app.payments.vnpay.hash-secret` | `VNPAY_HASH_SECRET` | required |
| `app.payments.vnpay.pay-url` | `VNPAY_PAY_URL` | VNPay sandbox payment page |
| `app.payments.vnpay.return-url` | `VNPAY_RETURN_URL` | `http://localhost:3000/payment/return` |

Put the sandbox terminal code and hash secret in `.local/application.properties`. VNPay must be able to reach the
IPN URL configured in the merchant portal; for local runs that needs a public tunnel.

## Tests

`PaymentPersistenceTest` commits Orders, which the immutability triggers never let anyone delete, so it runs only
against the disposable copy `CuratedArtworkMarketplace_Test`, selected by `TEST_DB_URL`. Recreate it from the
development database with `sqlcmd -S <server> -U <user> -C -i database/test/reset_test_database.sql`. Tests sign
notifications locally; no test calls VNPay.

## Known limits

- The VNPay contract was implemented from VNPay's published 2.1.0 integration guide; it has not yet been exercised
  against the sandbox, which needs the merchant's sandbox credentials and a reachable IPN URL.
- The VNPay transaction query/refund APIs are not used; reconciliation of exceptions is manual.
- The exception reason is only in the application log (B2). The amount received is recorded in the ledger's
  `UNAPPLIED` account, one transaction per exception event ([Artist proceeds](artist-proceeds.md#ledger)).
