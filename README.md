# stubio
Route Stremio Trailers and Streams to their appropriate 'External Player'

## Automated releases

### Trigger
A signed release APK is built and published automatically on every push to `main` (including PR merges).
The workflow is defined in [`.github/workflows/release.yml`](.github/workflows/release.yml).

### Required GitHub secrets
Set these in **Settings → Secrets and variables → Actions** before the first release:

| Secret | Description |
|---|---|
| `ANDROID_SIGNING_STORE_FILE` | Base64-encoded release keystore (e.g. `base64 -w0 release.keystore`) |
| `ANDROID_SIGNING_STORE_PASSWORD` | Keystore password |
| `ANDROID_SIGNING_KEY_ALIAS` | Key alias inside the keystore |
| `ANDROID_SIGNING_KEY_PASSWORD` | Key password |

### Expected output
Each successful run:
1. Creates and pushes a new Git tag (e.g. `v0.1.0`, `v0.1.1`, …).
2. Publishes a GitHub Release for that tag with auto-generated release notes.
3. Attaches the signed release APK to the release as a downloadable asset.
4. Uploads the APK as a workflow artifact (retained 30 days).

### Version tag generation
Tags follow [Semantic Versioning](https://semver.org/) (`vMAJOR.MINOR.PATCH`).  
- If no `v*` tag exists yet, the first release is tagged `v0.1.0`.  
- Each subsequent release bumps the **patch** component automatically (e.g. `v0.1.0 → v0.1.1`).  
- To start a new minor or major version, push a tag manually (e.g. `git tag v1.0.0 && git push origin v1.0.0`) before the next merge; the workflow will then continue from that base.

### Rotating / updating the signing keystore
1. Generate a new keystore or export the updated one.
2. Base64-encode it:
   - macOS: `base64 -i release.keystore | pbcopy` (copies to clipboard)
   - Linux: `base64 -w0 release.keystore` (copy the printed output manually)
3. In GitHub → Settings → Secrets, update `ANDROID_SIGNING_STORE_FILE` with the new value and update passwords/alias as needed.
4. The next merge to `main` will use the new keystore automatically.

> **Local builds** work without any secrets. Debug APKs are signed with the default debug key as usual.  
> Release builds locally will be unsigned unless you supply the Gradle properties or environment variables manually.
