# Stubio agent notes

- Stubio does **not** perform direct HTTP(S) network requests in app code.
- The manifest still declares `android.permission.INTERNET` intentionally to preserve compatibility with routed playback flows where external player apps handle network-backed stream URLs.
- Do not remove `INTERNET` purely on the assumption that Stubio itself does not open sockets; treat it as a compatibility contract unless product requirements explicitly change.
