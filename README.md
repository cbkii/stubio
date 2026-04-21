<p align="center">
  <img src="artwork/launcher/stubio-icon.svg" alt="Stubio app icon" width="160" />
</p>

# Stubio for Android TV

Stubio is a small routing app you select once, then it forwards playback to your preferred external players.

In other words: **choose Stubio as the app to open Stremio links**, and Stubio will choose the right player package for:
- **Streams** (movies/episodes)
- **Trailers** (YouTube-style links)

## What Stubio does

- Lets you configure **separate external players** for streams and trailers.
- Supports **primary + fallback** package for each type.
- Works with TV remote navigation (D-pad + OK) in setup.
- Shows installed apps in picker as `App Name (package.name)`.
- Validates incoming links/hosts before routing.
- Can report playback position back to Stremio when supported by the external player.

## Typical setup flow

1. Install Stubio on your Android TV / Google TV device.
2. Open **Stubio TV Setup**.
3. Pick stream player packages (primary/fallback).
4. Pick trailer player packages (primary/fallback).
5. Save settings.
6. In Stremio (or other source), choose **Stubio** when asked which app should open the link.

After this, Stubio acts as your single entry point and dispatches to the configured external apps.

## Configuration fields explained

- **Primary stream player package**: first choice for regular video streams.
- **Fallback stream player package**: used if primary is unavailable.
- **Primary trailer player package**: first choice for trailer/YouTube routing.
- **Fallback trailer player package**: used if primary trailer app is unavailable.
- **Additional allowed domains/IPs**: optional extra hosts you trust.

## About the “Additional allowed domains/IPs” setting

Stremio setups can vary (local server, custom domain, reverse proxy, LAN IP, etc).  
Stubio allows common Stremio hosts by default, but this field is for cases where your stream URL host is custom and gets blocked.

Only add hosts you trust. Examples:
- `media.example.com`
- `192.168.1.80`
- `10.0.0.25`

## Built-in fallback behavior

If your saved packages are missing/not launchable, Stubio tries known defaults:
- Streams: VLC, then MX Player.
- Trailers: SmartTube, then official YouTube.

## Troubleshooting

- **Wrong app opens**: re-open setup and confirm package names.
- **No app in picker**: verify app is installed for the same TV user/profile.
- **Nothing happens on play**: ensure your stream/trailer URL host is allowed, or add host in “Additional allowed domains/IPs”.

> ℹ️ NOTE: Stubio does **not** perform direct HTTP(S) network requests in app code. The `INTERNET` permission is intentionally kept as a compatibility contract for routed playback flows where external player apps handle network stream URLs.
