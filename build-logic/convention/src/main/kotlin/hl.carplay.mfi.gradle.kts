// Build-time MFi injection for :carplay:engine-android (docs/PLAN.md "CarPlay receiver", C2): the Gradle property
// or local.properties key `carplay.mfi.dir` names a directory holding identity.pk8 and certificate.p7b; every
// variant gets them as generated assets under build/. Without the key the assets are empty (IdentityUnavailable).
import com.android.build.api.variant.LibraryAndroidComponentsExtension

val copyMfiAssets = registerCopyMfiAssets()

extensions.configure<LibraryAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyMfiAssets, CopyMfiAssets::outputDirectory)
    }
}
