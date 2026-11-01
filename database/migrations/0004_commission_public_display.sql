-- F16: a Commission request records whether the Customer lets the Artist display the finished work publicly
-- (amendment D.11). The choice is required in every request and is not changed afterwards. Existing rows, if any,
-- get 0: no recorded consent means no display. See docs/commission-requests.md.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF COL_LENGTH('dbo.Commission', 'AllowPublicDisplay') IS NULL
    ALTER TABLE dbo.Commission ADD AllowPublicDisplay bit NOT NULL
        CONSTRAINT DF_Commission_AllowPublicDisplay DEFAULT (0);

COMMIT TRANSACTION;
