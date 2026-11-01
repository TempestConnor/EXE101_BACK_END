-- F18: a submitted Commission version, its file list and a Customer's revision request are evidence for later
-- disputes and refunds (§22.3, §22.10, §30), so the database refuses to change or delete them, as it does for sent
-- messages. A new submission is a new version. See docs/commission-delivery.md.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

EXEC ('CREATE OR ALTER TRIGGER dbo.TR_Deliverable_Immutable ON dbo.CommissionDeliverable AFTER UPDATE, DELETE AS
BEGIN
 SET NOCOUNT ON;
 THROW 51012, ''Submitted Commission versions are immutable.'', 1;
END;');

EXEC ('CREATE OR ALTER TRIGGER dbo.TR_DeliverableFile_Immutable ON dbo.DeliverableFile AFTER UPDATE, DELETE AS
BEGIN
 SET NOCOUNT ON;
 THROW 51013, ''Files of a submitted Commission version are immutable.'', 1;
END;');

EXEC ('CREATE OR ALTER TRIGGER dbo.TR_RevisionRequest_Immutable ON dbo.RevisionRequest AFTER UPDATE, DELETE AS
BEGIN
 SET NOCOUNT ON;
 THROW 51014, ''Revision requests are immutable.'', 1;
END;');

COMMIT TRANSACTION;
