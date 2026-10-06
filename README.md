# Pumpkin Java Wrapper

[![Build and test](https://github.com/Szabolcs05/pumpkin-java-wrapper/actions/workflows/ci.yml/badge.svg)](https://github.com/Szabolcs05/pumpkin-java-wrapper/actions/workflows/ci.yml)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)

Run the native [Pumpkin Minecraft server](https://pumpkinmc.org/) through a normal `java -jar` command. This is useful for hosting panels that accept a custom server JAR.

The launcher downloads the official executable for your platform, verifies its SHA-256, and connects the server console to your panel. It reuses that verified version until you explicitly update it. The JAR contains the launcher and its Java dependency; Pumpkin is downloaded directly from its own releases.

This is an independent project by **Szabolcs05**, unaffiliated with Pumpkin-MC, Mojang, or Microsoft.

## Quick start

1. Download `pumpkin-java-wrapper.jar` from [Releases](https://github.com/Szabolcs05/pumpkin-java-wrapper/releases/latest).
2. Upload it to your server directory and select it as the panel's custom server JAR. Renaming it to `server.jar` is fine.
3. Select **Java 17 or newer** and start the server. If you control the command, use:

   ```sh
   java -Xms32M -Xmx128M -jar pumpkin-java-wrapper.jar
   ```

4. Edit the generated `pumpkin.toml` for Pumpkin settings, including your allocated port. Type `stop` in the console to save and shut down before editing or updating.

The first run creates `pumpkin-wrapper.properties` and downloads the latest stable Pumpkin release. Server configuration and worlds live in the **working directory** used by the panel, regardless of where the JAR is stored. See the official [Pumpkin configuration guide](https://docs.pumpkinmc.org/config/introduction).

For a typical Java Edition allocation, set this in `pumpkin.toml`:

```toml
[networking.java]
address = "0.0.0.0:25565"
```

Replace `25565` with your allocated port. Pumpkin uses its own configuration; a panel's edits to vanilla `server.properties` do not configure Pumpkin.

## Hosting compatibility

The host must allow a Java process to launch a native executable, provide a writable server directory where executables may run, and permit outbound HTTPS to GitHub for installation and updates. A custom JAR selector alone does not guarantee these permissions. An offline installation can avoid the download requirement.

`-Xmx` and `-Xms` control the launcher's Java heap. **They do not cap Pumpkin's native memory use.** Your host's process or container limit still applies to the total. Keep the launcher heap small when possible; a panel's large `-Xms`, Aikar flags, or `AlwaysPreTouch` can reserve or commit memory that Pumpkin needs.

Automatic platform selection supports:

| System | CPU | Default platform |
| --- | --- | --- |
| Linux | x86-64 | `linux-x64-musl` |
| Linux | ARM64 | `linux-arm64-musl` |
| Windows | x86-64 | `windows-x64` |
| Windows | ARM64 | `windows-arm64` |
| macOS | Apple Silicon | `macos-arm64` |

Linux glibc builds are available through `linux-x64` and `linux-arm64`. A selected release must actually provide the requested asset. Other platforms, including Intel macOS, require a compatible executable supplied through `binary`. Platform detection uses the running JVM's OS and architecture.

Pumpkin is evolving quickly. Check its [release notes](https://github.com/Pumpkin-MC/Pumpkin/releases) and back up worlds before changing versions. This wrapper does not add vanilla features or plugin compatibility to Pumpkin.

## Configuration

The launcher creates `pumpkin-wrapper.properties` with these defaults:

```properties
release=latest
platform=auto
binary=
sha256=
shutdown-timeout-seconds=30
```

| Setting | Environment override | Meaning |
| --- | --- | --- |
| `release` | `PUMPKIN_WRAPPER_RELEASE` | `latest`, `canary`, `nightly`, or an exact upstream release tag. Default: `latest`. |
| `platform` | `PUMPKIN_WRAPPER_PLATFORM` | `auto` or a platform listed above. Default: `auto`. |
| `binary` | `PUMPKIN_WRAPPER_BINARY` | Path to your own Pumpkin executable; bypasses downloading and platform selection. Default: empty. |
| `sha256` | `PUMPKIN_WRAPPER_SHA256` | Optional extra SHA-256 pin: exactly 64 hexadecimal characters, without `sha256:`. Default: empty. |
| `shutdown-timeout-seconds` | `PUMPKIN_WRAPPER_SHUTDOWN_TIMEOUT_SECONDS` | Grace period for saving and stopping when the JVM shuts down, from 1 to 300 seconds. Default: `30`. |

Environment variables override the file. Files use UTF-8. Paths are relative to the server working directory; use forward slashes in Windows paths, for example `C:/servers/pumpkin/pumpkin.exe`. Unknown property names fail with an error to catch typos. See [the commented example](pumpkin-wrapper.properties.example).

### Versions and updates

`latest` means the latest stable upstream release **when that selection is first installed**. Normal starts verify and reuse the cached binary without a network request. The same applies to mutable `canary` and `nightly` tags. Changing `release` or `platform` selects a separate cached installation and may require a download.

For a specific release, use its exact tag:

```properties
release=0.2.0+26.3-26.51
```

After stopping the running wrapper and backing up your world, update and start:

```sh
java -jar pumpkin-java-wrapper.jar --wrapper-update
```

Or update without starting Pumpkin:

```sh
java -jar pumpkin-java-wrapper.jar --wrapper-update --wrapper-download-only
```

`--wrapper-update` refreshes the configured release selection. An exact tag continues selecting that tag; it does not switch to `latest`. A configured SHA-256 pin must match the new asset. The wrapper JAR itself is updated by replacing it with a newer wrapper release.

### Offline or custom binary

Download or build a compatible Pumpkin executable yourself, place it in the server directory, and configure:

```properties
binary=pumpkin
# On Windows: binary=pumpkin.exe
# Optional: paste the executable's trusted SHA-256 below.
sha256=
```

Local binaries work without network access. When `sha256` is set, the launcher verifies it on every start; otherwise it trusts the file you provided. `release` and `platform` are ignored for a local binary, and `--wrapper-update` is rejected. Replace the local executable yourself to update it.

### Cache and integrity

Official downloads require the asset's SHA-256 digest from the GitHub Releases API and are verified before use. Executables are stored by digest under `.pumpkin-wrapper/binaries/`; selection files record the resolved tag, asset, source URL, and hash. Every cached start rechecks the entire executable. A mismatch stops startup rather than executing or silently replacing the file. A SHA-256 pin adds a check against a digest you chose separately.

Keep `.pumpkin-wrapper/` for offline restarts. Deleting it removes the installed binary selection and causes a fresh download next time. Do not edit selection files. If a cached binary is damaged, remove only the affected executable named in the error, then run `--wrapper-update` to reinstall it. Digest checks rely on trusted GitHub metadata and a trusted local server directory; they are not an independent publisher signature.

## Command-line options

| Option | Effect |
| --- | --- |
| `--wrapper-help`, `--help`, `-h` | Show launcher help. |
| `--wrapper-version` | Show the wrapper version. |
| `--wrapper-config <path>` | Read or create a different wrapper properties file. |
| `--wrapper-update` | Refresh the configured Pumpkin release, then start it. |
| `--wrapper-download-only` | Install or verify Pumpkin and exit without starting it. |
| `--` | Forward every remaining argument verbatim to Pumpkin. |

Other arguments are forwarded. Common hosting-panel arguments `nogui` and `--nogui` are ignored before `--`. Arguments after `--` are passed without shell interpretation. Forwarding an argument does not mean the upstream Pumpkin version supports it; configure server options in `pumpkin.toml`.

Console input and output are connected to Pumpkin, and its exit code becomes the wrapper's exit code. Keep `use_console = true` in Pumpkin's `[commands]` configuration so console commands and graceful stop work. Only one wrapper can run in a server directory at a time. On JVM shutdown the launcher requests `stop`, waits for the configured grace period, and escalates termination if needed. A host's forced kill can bypass shutdown hooks, so allow enough time for saving.

## Build and autobuilds

Install a **JDK 17 or newer**, then build with the included Maven wrapper:

```sh
git clone https://github.com/Szabolcs05/pumpkin-java-wrapper.git
cd pumpkin-java-wrapper
./mvnw -B verify
```

On Windows PowerShell, use `.\mvnw.cmd -B verify`. The executable JAR is `target/pumpkin-java-wrapper.jar`. The first build downloads Maven and build dependencies.

[GitHub Actions](https://github.com/Szabolcs05/pumpkin-java-wrapper/actions) builds and tests pushes to `main`, pull requests, and manual runs on Linux, Windows, and macOS with Java 17 and 21. Each job uploads its JAR as an artifact. These checks validate the wrapper; they do not certify every hosting provider or Minecraft gameplay.

Pushing a version tag matching `pom.xml`, such as `v0.1.0`, triggers the release workflow. It publishes the JAR, `SHA256SUMS`, and `pumpkin-java-wrapper-source.zip`, which contains the repository source, build files, license notices, and source JARs for bundled Gson and Error Prone annotations. Wrapper releases do not bundle or republish Pumpkin executables.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| `UnsupportedClassVersionError` | Select Java 17 or newer in your panel. |
| Permission denied or process creation fails | Native child-process policy, executable permissions, and a filesystem mounted with `noexec`. |
| Missing native library or incompatible binary | Select a compatible platform. On Linux, try the default musl build or provide a locally built executable. |
| GitHub HTTP error | Release tag, outbound HTTPS, and GitHub API rate limits. A previously installed selection or local binary can start offline. |
| No asset for your platform | That upstream release lacks the selected build. Choose a supported release/platform or supply `binary`. |
| SHA-256 mismatch | Check your pin and the affected binary; follow the cache repair instructions above. |
| Panel says the server never started | Pumpkin emits `Server is now running.`; the panel may expect a vanilla-specific startup message. |
| Players cannot connect | Check the allocated port in `pumpkin.toml`, the host's network allocation, and the client version supported by your Pumpkin release. |

Report launcher problems in [this repository's issues](https://github.com/Szabolcs05/pumpkin-java-wrapper/issues). Report Pumpkin server bugs [upstream](https://github.com/Pumpkin-MC/Pumpkin/issues). Include versions and relevant logs, with credentials and private addresses removed.

## License and contributions

Copyright (c) 2026 Szabolcs05. The wrapper is licensed under **[GPL-3.0-only](LICENSE)**. You may use, copy, modify, and sell it. When distributing it or a modified version, preserve the required notices and provide the corresponding source under GPLv3 as required by the license. The license protects those freedoms; it does not prohibit forks, commercial use, or copying. Merely running a modified copy privately or as a hosted service does not itself trigger GPL source-distribution requirements.

[Pumpkin](https://github.com/Pumpkin-MC/Pumpkin) has its own GPLv3 license. Third-party Java and build components retain their licenses; see [NOTICE](NOTICE). Contributions are welcome—see [CONTRIBUTING.md](CONTRIBUTING.md). For vulnerabilities, see [SECURITY.md](SECURITY.md).
