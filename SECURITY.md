# Security

Security fixes target the latest released wrapper version. Use a current wrapper release and choose a Pumpkin release appropriate for your server.

## Reporting a vulnerability

Report suspected wrapper vulnerabilities privately using [GitHub's vulnerability reporting form](https://github.com/Szabolcs05/pumpkin-java-wrapper/security/advisories/new). Include the affected version, reproduction steps, expected impact, and a minimal example when possible. Do not include credentials, private worlds, or player data.

If private reporting is unavailable, open an issue requesting a private contact method without publishing exploit details. There is no guaranteed response time.

Vulnerabilities in Pumpkin itself should follow [Pumpkin's security policy](https://github.com/Pumpkin-MC/Pumpkin/blob/master/SECURITY.md).

## Security model

Official binary downloads use HTTPS with a restricted set of GitHub hosts, bounded redirects, download size limits, and network timeouts. The launcher requires the release asset's SHA-256 from GitHub, verifies downloaded bytes before installation, and verifies cached executables before every start. A failed verification stops launch. An optional `sha256` pin checks the selection against a digest chosen by the server owner.

These checks trust the official upstream repository, GitHub metadata, and the local server directory. They do not independently authenticate the publisher, sandbox Pumpkin, or protect files from another process with the same write permissions. A local `binary` setting intentionally executes the file selected by the server owner; its digest is checked only when `sha256` is configured.

The wrapper forwards arguments directly to the process without a shell. Pumpkin runs with the same operating-system permissions as the wrapper. Normal starts reuse the installed selection; updates are explicit. A JVM or container force-kill may prevent graceful shutdown.

Dependency updates are proposed through Dependabot. Build and release workflow actions are pinned to commit IDs, and release artifacts include checksums and corresponding source.
