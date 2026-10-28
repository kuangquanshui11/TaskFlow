$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
# The child process uses YAML test profiles; inherited connection overrides must not select a real database.
foreach ($setting in @('SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
        'SPRING_FLYWAY_ENABLED', 'MANAGEMENT_HEALTH_REDIS_ENABLED', 'SERVER_PORT')) {
    Remove-Item ('Env:' + $setting) -ErrorAction SilentlyContinue
}
& .\mvnw.cmd -pl taskflow-admin '-Dspring-boot.run.profiles=test,e2e' spring-boot:test-run
exit $LASTEXITCODE
