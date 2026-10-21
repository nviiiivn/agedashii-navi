# xnav.k for iPhone and iPad
CI (`.github/workflows/ios.yml`, on a Mac) fetches the map pack into `Resources/data/` and the voice into `Resources/voice/`, then runs `xcodegen generate` here to make `Xnav.xcodeproj` from `project.yml`.
`xcodebuild archive -scheme Xnav -configuration Release CODE_SIGNING_ALLOWED=NO` builds both halves: a build phase runs `./gradlew :ios:embedAndSignAppleFrameworkForXcode -PiosOnly` for the Kotlin framework (XnavKit) before the Swift compiles.
The `.app` from the archive goes into `Payload/`, zipped as `xnav-k.ipa`; it is unsigned, and SideStore or AltStore signs it on install.
