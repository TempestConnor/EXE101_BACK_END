-- F17b: a Commission Quote is paid in two installments of 50% (amendment D.13): 1 = the first payment, due after
-- acceptance, and 2 = the final payment, due after the Customer approves the work. Each installment has its own
-- Payment and its own proceeds, so an unpaid final payment (D.14) releases only the first. Order payments and Order
-- Item proceeds keep Installment NULL. The ledger's Artist accounts now name either the Order Item or the Quote.
-- No Quote Payment or Quote proceeds existed before this migration. See docs/commission-quote-payments.md.
SET XACT_ABORT ON;
-- Filtered indexes need it; sqlcmd turns it off by default.
SET QUOTED_IDENTIFIER ON;
BEGIN TRANSACTION;

IF COL_LENGTH('dbo.Payment', 'Installment') IS NULL
    ALTER TABLE dbo.Payment ADD Installment tinyint NULL;

IF COL_LENGTH('dbo.ArtistProceeds', 'Installment') IS NULL
    ALTER TABLE dbo.ArtistProceeds ADD Installment tinyint NULL;

IF COL_LENGTH('dbo.LedgerEntry', 'QuoteId') IS NULL
    ALTER TABLE dbo.LedgerEntry ADD QuoteId bigint NULL
        CONSTRAINT FK_LedgerEntry_Quote REFERENCES dbo.CommissionQuote (QuoteId);

-- The new columns are only visible to statements compiled after the ALTERs above, hence EXEC. A CHECK accepts
-- UNKNOWN, so "Installment IN (1, 2)" alone would let a NULL installment through; it is ruled out explicitly.
IF OBJECT_ID('dbo.CK_Payment_Installment', 'C') IS NOT NULL
    ALTER TABLE dbo.Payment DROP CONSTRAINT CK_Payment_Installment;

EXEC ('ALTER TABLE dbo.Payment ADD CONSTRAINT CK_Payment_Installment CHECK (
    (OrderId IS NOT NULL AND Installment IS NULL)
    OR (QuoteId IS NOT NULL AND Installment IS NOT NULL AND Installment IN (1, 2)))');

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_Payment_Quote' AND object_id = OBJECT_ID('dbo.Payment'))
    DROP INDEX UX_Payment_Quote ON dbo.Payment;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_Payment_QuoteInstallment'
    AND object_id = OBJECT_ID('dbo.Payment'))
    EXEC ('CREATE UNIQUE INDEX UX_Payment_QuoteInstallment ON dbo.Payment (QuoteId, Installment)
        WHERE QuoteId IS NOT NULL');

IF OBJECT_ID('dbo.CK_Proceeds_Installment', 'C') IS NOT NULL
    ALTER TABLE dbo.ArtistProceeds DROP CONSTRAINT CK_Proceeds_Installment;

EXEC ('ALTER TABLE dbo.ArtistProceeds ADD CONSTRAINT CK_Proceeds_Installment CHECK (
    (OrderItemId IS NOT NULL AND Installment IS NULL)
    OR (QuoteId IS NOT NULL AND Installment IS NOT NULL AND Installment IN (1, 2)))');

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_Proceeds_Quote' AND object_id = OBJECT_ID('dbo.ArtistProceeds'))
    DROP INDEX UX_Proceeds_Quote ON dbo.ArtistProceeds;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_Proceeds_QuoteInstallment'
    AND object_id = OBJECT_ID('dbo.ArtistProceeds'))
    EXEC ('CREATE UNIQUE INDEX UX_Proceeds_QuoteInstallment ON dbo.ArtistProceeds (QuoteId, Installment)
        WHERE QuoteId IS NOT NULL');

-- An Artist account line belongs to exactly one source: an Order Item or a Quote. Any line may name at most one.
IF OBJECT_ID('dbo.CK_LedgerEntry_ArtistAccount', 'C') IS NOT NULL
    ALTER TABLE dbo.LedgerEntry DROP CONSTRAINT CK_LedgerEntry_ArtistAccount;

EXEC ('ALTER TABLE dbo.LedgerEntry ADD CONSTRAINT CK_LedgerEntry_ArtistAccount CHECK (
    NOT (OrderItemId IS NOT NULL AND QuoteId IS NOT NULL)
    AND ((Account IN (''ARTIST_HELD'', ''ARTIST_AVAILABLE'', ''ARTIST_RELEASING'')
            AND ArtistId IS NOT NULL AND (OrderItemId IS NOT NULL OR QuoteId IS NOT NULL))
        OR (Account NOT IN (''ARTIST_HELD'', ''ARTIST_AVAILABLE'', ''ARTIST_RELEASING'') AND ArtistId IS NULL)))');

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_LedgerEntry_Quote'
    AND object_id = OBJECT_ID('dbo.LedgerEntry'))
    EXEC ('CREATE INDEX IX_LedgerEntry_Quote ON dbo.LedgerEntry (QuoteId, Account) INCLUDE (Amount)
        WHERE QuoteId IS NOT NULL');

COMMIT TRANSACTION;
