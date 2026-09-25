# BM64 Recomp mod server

This directory defines the public mod index consumed by the Android port.

The client is designed so server failure never blocks the game. Installed local mods continue to work offline.

## Index format

`index.json` uses `schema.json`. Each mod entry may contain:

- `id`: stable machine-readable mod ID
- `name`, `author`, `version`, `description`
- `download_url`: HTTPS URL to the mod package
- `sha256`: required integrity hash
- `filename`: optional preferred local filename
- `bm64_version`: optional compatible BM64 version
- `android_supported`: whether Android should offer the mod

Do not place game ROMs, copyrighted ROM-derived assets, or other unauthorized content in this repository.
