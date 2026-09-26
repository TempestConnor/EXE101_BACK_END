param(
    [ValidateSet('Generate', 'Verify', 'Run')]
    [string]$Action = 'Run'
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$localDir = Join-Path (Get-Location) '.local'
New-Item -ItemType Directory -Force $localDir | Out-Null
$configPath = Join-Path $localDir 'database.json'
if (Test-Path $configPath) {
    $config = Get-Content $configPath -Raw | ConvertFrom-Json
    if (-not $env:DB_USERNAME) { $env:DB_USERNAME = $config.username }
    if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = $config.password }
}
if (-not $env:DB_USERNAME -or -not $env:DB_PASSWORD) {
    throw 'Set DB_USERNAME and DB_PASSWORD, or create .local/database.json using tooling/database.example.json.'
}

# Local SQL Express development only. Override DB_URL for another server.
if (-not $env:DB_URL) {
    $instance = (Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\Instance Names\SQL').SQLEXPRESS
    $tcp = Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\$instance\MSSQLServer\SuperSocketNetLib\Tcp\IPAll"
    $port = if ($tcp.TcpPort) { $tcp.TcpPort } else { $tcp.TcpDynamicPorts }
    if (-not $port -or $port -eq '0') { throw 'No SQL Express TCP port found. Set DB_URL explicitly.' }
    $env:DB_URL = "jdbc:sqlserver://localhost:$port;databaseName=CuratedArtworkMarketplace;encrypt=true;trustServerCertificate=true;loginTimeout=10"
}

switch ($Action) {
    'Generate' {
        # Properties.load uses ISO-8859-1 and treats backslashes as escapes.
        function Escape-Property([string]$value) {
            $value = $value.Replace('\', '\\')
            -join ($value.ToCharArray() | ForEach-Object {
                if ([int]$_ -gt 127) { '\u{0:x4}' -f [int]$_ } else { [string]$_ }
            })
        }
        $properties = @(
            'hibernate.connection.driver_class=com.microsoft.sqlserver.jdbc.SQLServerDriver'
            ('hibernate.connection.url=' + (Escape-Property $env:DB_URL))
            ('hibernate.connection.username=' + (Escape-Property "$env:DB_USERNAME"))
            ('hibernate.connection.password=' + (Escape-Property "$env:DB_PASSWORD"))
            'hibernate.default_catalog=CuratedArtworkMarketplace'
            'hibernate.default_schema=dbo'
        )
        $properties | Set-Content (Join-Path $localDir 'hibernate.properties') -Encoding ASCII
        & .\mvnw.cmd -B clean -Pgenerate-entities hibernate-tools:hbm2java
        if ($LASTEXITCODE -ne 0) { throw 'Hibernate Tools generation failed.' }
        & .\mvnw.cmd -B dependency:build-classpath '-Dmdep.outputFile=.local/classpath.txt'
        if ($LASTEXITCODE -ne 0) { throw 'Could not resolve generator classpath.' }
        $classpath = (Get-Content (Join-Path $localDir 'classpath.txt') -Raw).Trim()
        & java --class-path $classpath tooling/NormalizeEntities.java target/generated-entities/com/exe101/entity
    }
    'Verify' {
        & .\mvnw.cmd -B verify -Dtest=DatabaseMappingTest
    }
    'Run' {
        & .\mvnw.cmd -B spring-boot:run
    }
}
if ($LASTEXITCODE -ne 0) { throw "Maven $Action failed ($LASTEXITCODE)." }
