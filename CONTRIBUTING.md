# Contributing

Start with the [build guide](docs/BUILDING.md) and [architecture](docs/ARCHITECTURE.md). Keep each pull request focused on one problem. For a large feature or architecture change, open an issue before implementation.

## Changes

Follow the surrounding Kotlin code and reuse the existing Material 3 Expressive components. Keep parsing, routing and connection-state changes covered by regression tests where practical.

Before submitting:

- Run `./gradlew :shared:desktopTest` (`.\gradlew.bat :shared:desktopTest` on Windows).
- Build the platform affected by the change.
- Describe the problem, resulting behavior and checks performed.
- Include screenshots for UI changes and the tested device or OS for platform-specific changes.

Documentation-only changes need working links and accurate commands, not an application build.

## Translations

Keep resource keys, format placeholders and XML escaping intact. Check the changed screen for clipping. The English, Russian and Simplified Chinese READMEs should describe the same behavior.

## Reports and examples

Use [Issues](https://github.com/YouRooni/Zefir/issues) for bugs and feature requests. Reports can be written in English or Russian. Use placeholders instead of real subscription URLs, keys, device identifiers and credentials. Report vulnerabilities privately through [SECURITY.md](SECURITY.md).

## Licensing

Contributions use the project's [GPL v3.0 license](LICENSE). Preserve upstream notices when reusing code, and record the source, revision and changes in [NOTICE](NOTICE). Do not commit generated binaries, signing keys, personal configuration or local build output.
