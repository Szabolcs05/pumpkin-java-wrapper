# Contributing

Thanks for helping improve Pumpkin Java Wrapper. Bug reports, documentation fixes, and focused pull requests are welcome.

## Development

Use JDK 17 or newer. Build and run the checks with:

```sh
./mvnw -B verify
```

On Windows PowerShell:

```powershell
.\mvnw.cmd -B verify
```

The executable JAR is `target/pumpkin-java-wrapper.jar`. Test its entry point with:

```sh
java -jar target/pumpkin-java-wrapper.jar --wrapper-help
```

Keep the source compatible with Java 17. CI covers Java 17 and 21 on Linux, Windows, and macOS. Use a separate, disposable server directory when trying a real Pumpkin binary so that development does not touch an existing world.

## Pull requests

- Describe the problem, the resulting behavior, and how you checked the change.
- Keep changes focused and update the README or configuration example when behavior changes.
- Add meaningful regression coverage for download integrity, cache selection, argument handling, or process lifecycle changes. Avoid live-network requirements in automated tests.
- Preserve explicit updates, digest verification, argument forwarding without a shell, and graceful shutdown.
- Keep credentials, generated server configuration, binaries, and worlds out of commits.

By submitting a contribution, you agree to license that contribution under this project's GPL-3.0-only license. Retain existing copyright and third-party license notices. No contributor license agreement is required.

## Reporting problems

Open an [issue](https://github.com/Szabolcs05/pumpkin-java-wrapper/issues) with your OS/CPU, Java version, wrapper version, Pumpkin release, steps to reproduce, and relevant logs. Remove tokens, passwords, and private addresses. A minimal example using a disposable server directory is especially useful.

Gameplay or Pumpkin server bugs belong in [Pumpkin's issue tracker](https://github.com/Pumpkin-MC/Pumpkin/issues). Report potential wrapper vulnerabilities through [SECURITY.md](SECURITY.md).

## Releases

Maintainers set the version in `pom.xml`, run the checks, and push the matching `v<version>` tag; for example, `v0.1.0` matches `0.1.0`. The release workflow builds and tests, then publishes the executable JAR, a corresponding-source ZIP including Gson sources, and SHA-256 checksums.

Review the generated release artifacts before announcing a release. Do not add Pumpkin executables to this repository or the wrapper's release assets; installation downloads them directly from the official upstream repository.
