# Play Store listing assets

Assets for the Google Play listing of `com.zungvi.messenger`, kept separate from
`fastlane/metadata/android/` on purpose: that tree is Snikket-era metadata used by F-Droid, and
its images are still Snikket's artwork.

    en-US/icon-512.png              512x512   Play "app icon"
    en-US/featureGraphic.png       1024x500   Play "feature graphic"
    en-US/phoneScreenshots/                   4-8 shots, 9:16, >=1080px short side
    tools/render-icon.py                      regenerates the icon from the launcher vector
    tools/render-feature-graphic.py            regenerates the banner

Both renderers read the launcher vector at
`src/conversations/res/drawable/ic_launcher_foreground.xml`, so the artwork stays in step with
the app rather than being a stale export. The banner takes the name and tagline from the
environment, which matters while the Lisolo/Zungvi naming is unsettled:

    APP_NAME=Zungvi TAGLINE="..." python3 tools/render-feature-graphic.py out.png

Play requires the screenshots to be 16:9 or 9:16 with each side between 320px and 3840px, and
wants at least four at 1080px or more to be eligible for promotion. The Snikket screenshots in
the fastlane tree are 720x1440, which is 1:2 and under that floor, so they do not qualify.
