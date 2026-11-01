-- F15: refunds are money events too. An approved refund moves what the item still owed (the Artist's held proceeds,
-- the manufacturing cost, the tax and, for the last physical item, the Order's shipping) into REFUND_PAYABLE; a
-- succeeded transfer pays it out of CASH. A failed transfer writes nothing, so the money stays owed until a retry
-- succeeds. Only the CHECK constraints change. See docs/refunds.md.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID('dbo.CK_LedgerTransaction_Type', 'C') IS NOT NULL
    ALTER TABLE dbo.LedgerTransaction DROP CONSTRAINT CK_LedgerTransaction_Type;

ALTER TABLE dbo.LedgerTransaction ADD CONSTRAINT CK_LedgerTransaction_Type CHECK (Type IN ('PAYMENT_RECEIVED',
    'PAYMENT_UNAPPLIED', 'PROCEEDS_RELEASED', 'RELEASE_CREATED', 'RELEASE_PAID', 'RELEASE_FAILED', 'REFUND_APPROVED',
    'REFUND_PAID'));

IF OBJECT_ID('dbo.CK_LedgerTransaction_SourceType', 'C') IS NOT NULL
    ALTER TABLE dbo.LedgerTransaction DROP CONSTRAINT CK_LedgerTransaction_SourceType;

ALTER TABLE dbo.LedgerTransaction ADD CONSTRAINT CK_LedgerTransaction_SourceType CHECK (SourceType IN ('PAYMENT',
    'PROVIDER_EVENT', 'PROCEEDS', 'PAYOUT', 'REFUND_REQUEST', 'REFUND_TRANSFER'));

IF OBJECT_ID('dbo.CK_LedgerEntry_Account', 'C') IS NOT NULL
    ALTER TABLE dbo.LedgerEntry DROP CONSTRAINT CK_LedgerEntry_Account;

ALTER TABLE dbo.LedgerEntry ADD CONSTRAINT CK_LedgerEntry_Account CHECK (Account IN ('CASH', 'ARTIST_HELD',
    'ARTIST_AVAILABLE', 'ARTIST_RELEASING', 'MANUFACTURER_PAYABLE', 'SHIPPING', 'TAX_PAYABLE', 'UNAPPLIED',
    'REFUND_PAYABLE'));

COMMIT TRANSACTION;
