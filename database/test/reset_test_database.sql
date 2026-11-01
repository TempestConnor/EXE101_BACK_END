-- Recreates CuratedArtworkMarketplace_Test as a copy of the development database (schema, triggers and data).
-- Payment integration tests commit Orders, which the immutability triggers never let anyone delete, so they run
-- only against this disposable copy. Run it again to start from a clean copy:
--   sqlcmd -S <server> -U <user> -C -i database/test/reset_test_database.sql
SET NOCOUNT ON;

DECLARE @source sysname = N'CuratedArtworkMarketplace';
DECLARE @target sysname = N'CuratedArtworkMarketplace_Test';
DECLARE @backupDir nvarchar(4000) = CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(4000));
DECLARE @dataDir nvarchar(4000) = CAST(SERVERPROPERTY('InstanceDefaultDataPath') AS nvarchar(4000));
DECLARE @backupFile nvarchar(4000) = @backupDir + N'\' + @target + N'_source.bak';
DECLARE @dataName sysname = (SELECT name FROM sys.master_files WHERE database_id = DB_ID(@source) AND type = 0);
DECLARE @logName sysname = (SELECT name FROM sys.master_files WHERE database_id = DB_ID(@source) AND type = 1);
DECLARE @dataFile nvarchar(4000) = @dataDir + @target + N'.mdf';
DECLARE @logFile nvarchar(4000) = @dataDir + @target + N'_log.ldf';

-- COPY_ONLY leaves the development database's backup chain unchanged.
BACKUP DATABASE @source TO DISK = @backupFile WITH COPY_ONLY, INIT, FORMAT;

IF DB_ID(@target) IS NOT NULL
BEGIN
    DECLARE @drop nvarchar(max) = N'ALTER DATABASE ' + QUOTENAME(@target)
        + N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE ' + QUOTENAME(@target) + N';';
    EXEC (@drop);
END;

RESTORE DATABASE @target FROM DISK = @backupFile
    WITH MOVE @dataName TO @dataFile, MOVE @logName TO @logFile, REPLACE;
