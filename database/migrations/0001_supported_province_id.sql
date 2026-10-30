-- F04: give SupportedProvince a numeric key so AdminAudit.TargetId (bigint) can reference it.
-- ProvinceCode stays the unique business key. Nothing references SupportedProvince by foreign key.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF COL_LENGTH('dbo.SupportedProvince', 'ProvinceId') IS NULL
BEGIN
    DECLARE @pk sysname = (
        SELECT name FROM sys.key_constraints
        WHERE parent_object_id = OBJECT_ID('dbo.SupportedProvince') AND type = 'PK');
    -- The original primary key has a server-generated name that differs between databases.
    EXEC ('ALTER TABLE dbo.SupportedProvince DROP CONSTRAINT ' + @pk);

    ALTER TABLE dbo.SupportedProvince ADD ProvinceId bigint IDENTITY(1,1) NOT NULL;
    -- Separate batch: the new column is not visible to statements compiled with this one.
    EXEC ('ALTER TABLE dbo.SupportedProvince ADD CONSTRAINT PK_SupportedProvince PRIMARY KEY (ProvinceId)');
    ALTER TABLE dbo.SupportedProvince ADD CONSTRAINT UX_SupportedProvince_Code UNIQUE (ProvinceCode);
END;

COMMIT TRANSACTION;
