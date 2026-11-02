-- F20: each Artist publishes the terms a Commission refund follows (text and images), approved 2 November 2026.
-- Publishing again adds a version and never rewrites an earlier one, and accepting a Quote records the version the
-- Customer agreed to, so the terms behind a refund or a dispute stay provable. Quotes accepted before this have
-- no version (NULL). See docs/artist-terms.md.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID('dbo.ArtistTerms', 'U') IS NULL
    CREATE TABLE dbo.ArtistTerms (
        ArtistTermsId bigint IDENTITY(1,1) NOT NULL CONSTRAINT PK_ArtistTerms PRIMARY KEY,
        ArtistId bigint NOT NULL CONSTRAINT FK_ArtistTerms_Artist REFERENCES dbo.ArtistProfile(ArtistId),
        VersionNo int NOT NULL CONSTRAINT CK_ArtistTerms_Version CHECK (VersionNo >= 1),
        TermsText nvarchar(max) NOT NULL CONSTRAINT CK_ArtistTerms_Text CHECK (LEN(TermsText) >= 1),
        PublishedAt datetimeoffset(0) NOT NULL,
        CONSTRAINT UQ_ArtistTerms_Version UNIQUE (ArtistId, VersionNo)
    );

IF OBJECT_ID('dbo.ArtistTermsImage', 'U') IS NULL
    CREATE TABLE dbo.ArtistTermsImage (
        ArtistTermsImageId bigint IDENTITY(1,1) NOT NULL CONSTRAINT PK_ArtistTermsImage PRIMARY KEY,
        ArtistTermsId bigint NOT NULL CONSTRAINT FK_ArtistTermsImage_Terms REFERENCES dbo.ArtistTerms(ArtistTermsId),
        FileId bigint NOT NULL CONSTRAINT FK_ArtistTermsImage_File REFERENCES dbo.StoredFile(FileId),
        SortOrder int NOT NULL CONSTRAINT CK_ArtistTermsImage_SortOrder CHECK (SortOrder >= 0),
        CONSTRAINT UQ_ArtistTermsImage_Order UNIQUE (ArtistTermsId, SortOrder),
        CONSTRAINT UQ_ArtistTermsImage_File UNIQUE (ArtistTermsId, FileId)
    );

IF COL_LENGTH('dbo.CommissionQuote', 'ArtistTermsId') IS NULL
    ALTER TABLE dbo.CommissionQuote ADD ArtistTermsId bigint NULL
        CONSTRAINT FK_Quote_ArtistTerms REFERENCES dbo.ArtistTerms(ArtistTermsId);

EXEC ('CREATE OR ALTER TRIGGER dbo.TR_ArtistTerms_Immutable ON dbo.ArtistTerms AFTER UPDATE, DELETE AS
BEGIN
 SET NOCOUNT ON;
 THROW 51015, ''Published Artist terms are immutable.'', 1;
END;');

EXEC ('CREATE OR ALTER TRIGGER dbo.TR_ArtistTermsImage_Immutable ON dbo.ArtistTermsImage AFTER UPDATE, DELETE AS
BEGIN
 SET NOCOUNT ON;
 THROW 51016, ''Images of published Artist terms are immutable.'', 1;
END;');

-- Unchanged from the baseline apart from ArtistTermsId: the version an accepted Quote was agreed under is part of
-- its terms.
EXEC ('CREATE OR ALTER TRIGGER dbo.TR_Quote_Terms_Immutable ON dbo.CommissionQuote AFTER UPDATE, DELETE AS
BEGIN
 SET NOCOUNT ON;
 IF EXISTS(SELECT 1 FROM deleted) AND NOT EXISTS(SELECT 1 FROM inserted)
  THROW 51004, ''Quotes cannot be deleted.'', 1;
 IF EXISTS(SELECT 1 FROM deleted WHERE Status IN (''ACCEPTED'',''PAID''))
 AND (NOT EXISTS(SELECT 1 FROM inserted) OR EXISTS(
   SELECT 1 FROM deleted d JOIN inserted i ON i.QuoteId=d.QuoteId
   WHERE i.CommissionId<>d.CommissionId OR i.SequenceNo<>d.SequenceNo
      OR i.ScopeText<>d.ScopeText OR i.ExpectedCompletionTerms<>d.ExpectedCompletionTerms
      OR i.Price<>d.Price OR i.TaxAmount<>d.TaxAmount OR i.CurrencyCode<>d.CurrencyCode
      OR i.FeeRate<>d.FeeRate OR i.FeeAmount<>d.FeeAmount OR i.ArtistProceeds<>d.ArtistProceeds
      OR i.AcceptedAt<>d.AcceptedAt
      OR ISNULL(i.ArtistTermsId, -1)<>ISNULL(d.ArtistTermsId, -1)))
  THROW 51004, ''Accepted quote terms are immutable.'', 1;
END;');

COMMIT TRANSACTION;
