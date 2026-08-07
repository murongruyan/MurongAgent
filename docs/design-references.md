# Design References

MurongAgent studies public open-source projects for interaction and data-model ideas. Unless a file says otherwise, these references do not change MurongAgent's MIT license and do not mean upstream source or assets were copied.

## cc-switch

- Project: https://github.com/farion1231/cc-switch
- License: MIT
- Reference scope: account-oriented quota presentation, separating official quota from local cost estimates, and structured provider metadata such as protocol, website, API-key entry, endpoints, and model defaults.
- MurongAgent implementation: original Kotlin and Jetpack Compose code integrated with `ProviderConfig`, `ProviderRegistry`, the Codex account pool, and Android Keystore. No cc-switch TypeScript, icons, themes, or UI assets are included.

If source code or assets are intentionally vendored in the future, their original copyright and license text must be added next to the vendored material and to the release notices.
