-- F14: an append-only, double-entry money ledger. Each money event is one LedgerTransaction whose LedgerEntry lines
-- add up to 0 (debits positive, credits negative). UX_LedgerTransaction_Source records each event once; the
-- triggers make both tables immutable, so a mistake is corrected by a new transaction, never by an edit.
-- See docs/artist-proceeds.md.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID('dbo.LedgerTransaction', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.LedgerTransaction (
        TransactionId bigint IDENTITY(1,1) NOT NULL CONSTRAINT PK_LedgerTransaction PRIMARY KEY,
        Type varchar(32) NOT NULL CONSTRAINT CK_LedgerTransaction_Type CHECK (Type IN ('PAYMENT_RECEIVED',
            'PAYMENT_UNAPPLIED', 'PROCEEDS_RELEASED', 'RELEASE_CREATED', 'RELEASE_PAID', 'RELEASE_FAILED')),
        SourceType varchar(32) NOT NULL CONSTRAINT CK_LedgerTransaction_SourceType CHECK (SourceType IN ('PAYMENT',
            'PROVIDER_EVENT', 'PROCEEDS', 'PAYOUT')),
        SourceId bigint NOT NULL,
        OccurredAt datetimeoffset(0) NOT NULL,
        CONSTRAINT UX_LedgerTransaction_Source UNIQUE (Type, SourceType, SourceId)
    );
END;

IF OBJECT_ID('dbo.LedgerEntry', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.LedgerEntry (
        EntryId bigint IDENTITY(1,1) NOT NULL CONSTRAINT PK_LedgerEntry PRIMARY KEY,
        TransactionId bigint NOT NULL CONSTRAINT FK_LedgerEntry_Transaction
            REFERENCES dbo.LedgerTransaction (TransactionId),
        Account varchar(32) NOT NULL CONSTRAINT CK_LedgerEntry_Account CHECK (Account IN ('CASH', 'ARTIST_HELD',
            'ARTIST_AVAILABLE', 'ARTIST_RELEASING', 'MANUFACTURER_PAYABLE', 'SHIPPING', 'TAX_PAYABLE', 'UNAPPLIED')),
        ArtistId bigint NULL CONSTRAINT FK_LedgerEntry_Artist REFERENCES dbo.ArtistProfile (ArtistId),
        OrderItemId bigint NULL CONSTRAINT FK_LedgerEntry_OrderItem REFERENCES dbo.OrderItem (OrderItemId),
        Amount decimal(19,4) NOT NULL CONSTRAINT CK_LedgerEntry_Amount CHECK (Amount <> 0),
        CurrencyCode char(3) NOT NULL CONSTRAINT DF_LedgerEntry_Currency DEFAULT ('VND')
            CONSTRAINT CK_LedgerEntry_Currency CHECK (CurrencyCode = 'VND'),
        -- Money owed to an Artist is always tied to that Artist and the item that earned it.
        CONSTRAINT CK_LedgerEntry_ArtistAccount CHECK (
            (Account IN ('ARTIST_HELD', 'ARTIST_AVAILABLE', 'ARTIST_RELEASING')
                AND ArtistId IS NOT NULL AND OrderItemId IS NOT NULL)
            OR (Account NOT IN ('ARTIST_HELD', 'ARTIST_AVAILABLE', 'ARTIST_RELEASING') AND ArtistId IS NULL))
    );
    CREATE INDEX IX_LedgerEntry_Transaction ON dbo.LedgerEntry (TransactionId);
    CREATE INDEX IX_LedgerEntry_Item ON dbo.LedgerEntry (OrderItemId, Account) INCLUDE (Amount);
END;

EXEC ('CREATE OR ALTER TRIGGER dbo.TR_LedgerTransaction_Immutable ON dbo.LedgerTransaction AFTER UPDATE, DELETE AS
BEGIN
 SET NOCOUNT ON;
 THROW 51010, ''Ledger transactions are immutable.'', 1;
END;');

EXEC ('CREATE OR ALTER TRIGGER dbo.TR_LedgerEntry_Immutable ON dbo.LedgerEntry AFTER UPDATE, DELETE AS
BEGIN
 SET NOCOUNT ON;
 THROW 51011, ''Ledger entries are immutable.'', 1;
END;');

COMMIT TRANSACTION;
