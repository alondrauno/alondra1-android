<p align="center"><img src="docs/images/alondra1-logo-512.png" alt="Alondra1 logo" width="128"></p>

# Alondra1 for Android

[![ci](https://github.com/nikita4sea/alondra1-android/actions/workflows/ci.yml/badge.svg)](https://github.com/nikita4sea/alondra1-android/actions/workflows/ci.yml)

Planned Android client of Alondra1 ([spec §11](https://github.com/nikita4sea/alondra1-backend/blob/main/docs/BACKEND_SPEC.md#11-client-requirements)). **Nothing is implemented yet.**

## Scope

Local tasks and portable settings in Room/SQLite with an outbox, email-code sign-in, Personal/Family/Organization space selection, and sync against the same API contract and fixtures as Windows. Credentials go to the Android Keystore; background sync uses WorkManager (best effort, subject to OS constraints). The mail, calendar, and organizer integrations of the Windows app are not part of this scope.

Details: [docs/CLIENT-REQUIREMENTS.md](docs/CLIENT-REQUIREMENTS.md).

## Getting started

When the project is created, put the Android Studio project (Gradle wrapper at the repository root) here. Expected commands:

```sh
./gradlew lint test assembleDebug
```

Cloud settings for development (API base URL and so on) go in a local `.env` copied from [.env.example](.env.example); never commit it or any credentials.

## CI

The [`ci`](.github/workflows/ci.yml) workflow checks Markdown links and scans for secrets on every push and pull request. Its build job starts automatically once the project files exist (it is skipped until then).

## Related repositories

[alondra1-backend](https://github.com/nikita4sea/alondra1-backend) (cloud API and contract) · [alondra1-windows](https://github.com/nikita4sea/alondra1-windows) (reference client) · [alondra1-www](https://github.com/nikita4sea/alondra1-www)

## License

[MIT](LICENSE)
