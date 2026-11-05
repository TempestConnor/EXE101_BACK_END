# EXE101 backend

Spring Boot 4.1.1, Spring Data JPA, Maven Wrapper, and Microsoft SQL Server.
The Java compilation target is 21; use JDK 21 or newer and set `JAVA_HOME` to that JDK.
Maven does not need to be installed separately.

## Local development (PowerShell)

The existing database is `CuratedArtworkMarketplace` on `.\SQLEXPRESS`.
SQL authentication is used. Local connection settings are in `.local/database.json`,
which is ignored by Git. On another machine, copy `tooling/database.example.json`
to that location and fill in the URL and credentials. Replace the example URL's
host and port with your SQL Server TCP address.

```powershell
.\scripts\dev.ps1 Verify
.\scripts\dev.ps1 Run
```

### Setting up a new database

The baseline schema is not in this repository: it is the team's schema script (`EXE101_SQL_Schema.sql` in the
specification), shared separately. On a new machine:

1. Create `CuratedArtworkMarketplace` from the baseline script.
2. Apply every file in [database/migrations](database/migrations) on top, one at a time, in number order. Each is
   safe to re-run.

   ```powershell
   Get-ChildItem database\migrations\*.sql | Sort-Object Name | ForEach-Object {
       sqlcmd -S <server> -U <user> -C -d CuratedArtworkMarketplace -b -i $_.FullName
   }
   ```

3. Run `.\scripts\dev.ps1 Verify`. A missing migration shows up here, or at startup, as a schema validation error
   (`ddl-auto=validate`) naming the missing table or column.

The test database `CuratedArtworkMarketplace_Test` is a copy of this one, made by
[database/test/reset_test_database.sql](database/test/reset_test_database.sql); apply new migrations to both.

Customer authentication requires `JWT_SECRET` before running the application;
see [F01 setup and API contract](docs/customer-authentication.md).

`Verify` compiles/packages the application, validates all entity mappings, and
executes a read-only query for each of the 49 entities. It does not insert or
update data. `Run` starts the backend on port 8080. Customer registration, token
login and the authenticated profile endpoint are implemented.

The script loads `url`, `username`, and `password` from `.local/database.json`.
Environment variables `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` override the
corresponding file values. A missing URL produces a configuration error.
The example localhost URL trusts the development
server's certificate. For deployed environments, supply a JDBC URL with normal
certificate verification and the appropriate credentials through environment variables.

When launching Java directly from an IDE or with `.\mvnw.cmd spring-boot:run`, the
application reads `.local/application.properties` (ignored by Git), which can define
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` and `STORAGE_ROOT`. Real environment
variables with the same names still take precedence. The JSON file is loaded only by
`scripts/dev.ps1`. Artwork preview images are stored under `STORAGE_ROOT`
(default `.local/storage`).

A storefront on another origin (its own domain, or `http://localhost:3000` calling `:8080` directly) needs that
origin in `CORS_ALLOWED_ORIGINS`, comma-separated exact origins such as
`https://shop.example.com,http://localhost:3000`. Wildcards are refused at startup. Leave it empty when the
storefront reaches the API through the same origin, such as a reverse proxy or the Vite dev proxy.

Behind a reverse proxy (nginx, ngrok, a Docker network), the buyer's address sent to VNPay is taken from
`X-Forwarded-For`, which Tomcat trusts only from loopback and private-range addresses. A proxy on a public address,
such as a cloud load balancer, must be listed in `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` (a regular expression).

Uploads are limited to 200 MB per file (201 MB per request). Tomcat stops reading an upload far over the limit and
closes the connection, so the browser may see a network error instead of `413 PAYLOAD_TOO_LARGE`. Behind nginx, set
`client_max_body_size 201m;` so nginx answers oversized uploads with its own 413 before they reach the backend; the
storefront should also check the file size before uploading.

To build without connecting to SQL Server:

```powershell
.\mvnw.cmd clean package
```

The database tests run only when `DB_URL` is set. The helper sets it for `Verify`.
VS Code should import `pom.xml` as a Maven project; reload the window if it still
shows the previous unmanaged Java project.

## Generated entities

`src/main/java/com/exe101/entity` contains 49 entities and five composite ID classes.
SQL Server's `sysdiagrams` table is excluded. Hibernate Tools 7.3.13.Final produces
the Java source; `tooling/NormalizeEntities.java` reads live column metadata to
correct SQL Server details:

- `nvarchar`/`nchar` map to Unicode strings with database lengths.
- `datetimeoffset` maps to `OffsetDateTime` with native time-zone storage.
- `decimal` maps to `BigDecimal`; binary columns map to `byte[]`.
- `rowversion` is a database-generated optimistic locking version, excluded from writes.
- Identity keys and composite keys are generated by Hibernate Tools.

Foreign keys currently remain scalar ID fields, not `@ManyToOne`/`@OneToMany`
associations. Hibernate Tools fails on the schema's overlapping composite foreign
keys when association generation is enabled. The database still enforces those
relationships. Add reviewed associations as features need them; do not map a
shared foreign-key column as writable in two properties.

The SQL database is the schema authority. `ddl-auto=validate` and disabled SQL
initialization prevent application startup from creating or updating tables.
Filtered indexes, checks, defaults, and cross-table business rules stay in SQL.
Generated unique annotations are removed because they cannot accurately describe
filtered unique indexes. Services must supply required values (including status,
timestamps, and flags) when inserting; database defaults are not Java field initializers.
Use `OffsetDateTime` values in UTC to follow this schema's timestamp convention.

## Regenerate after a schema change

```powershell
.\scripts\dev.ps1 Generate
```

This cleans `target`, runs Hibernate Tools through the opt-in `generate-entities`
Maven profile, and applies the metadata corrections. Results are staged in
`target/generated-entities/com/exe101/entity`; normal builds never regenerate source.
Compare the staging files with `src/main/java/com/exe101/entity`, apply the intended
changes (including removing obsolete entities), then run `Verify` again. Adjust
the expected entity count in `DatabaseMappingTest` when application tables change.

Generator configuration: `tooling/hibernate.reveng.xml` and the Maven profile in
`pom.xml`. The helper creates an ignored `.local/hibernate.properties` for the
generation connection. Keep `.local` private.

References: [Hibernate Tools](https://hibernate.org/tools/) and
[Microsoft JDBC connection properties](https://learn.microsoft.com/sql/connect/jdbc/setting-the-connection-properties).
