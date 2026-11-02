-- F20: the refund of a cancelled, paid Commission is a whole percentage of the held first payment, entered by the
-- Artist under their own terms and confirmed by the Customer, or set by the Admin (approved 2 November 2026). The
-- percentage is kept on the request; Order Item requests never carry one. Existing rows get NULL.
-- See docs/commission-refunds.md.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF COL_LENGTH('dbo.RefundRequest', 'RefundPercent') IS NULL
    ALTER TABLE dbo.RefundRequest ADD RefundPercent tinyint NULL;

IF OBJECT_ID('dbo.CK_RefundRequest_Percent', 'C') IS NULL
    EXEC ('ALTER TABLE dbo.RefundRequest ADD CONSTRAINT CK_RefundRequest_Percent CHECK (RefundPercent IS NULL
        OR (CommissionId IS NOT NULL AND RefundPercent BETWEEN 0 AND 100))');

COMMIT TRANSACTION;
