plugins {
    id("titan.android.feature")
}

android {
    namespace = "com.titan.feature.settings"
}

dependencies {
    // The one thing this module needs that the feature convention does not supply:
    // `rememberLauncherForActivityResult`, which is how the backup screen opens and creates its
    // files. Declared rather than leaned on through `navigation-compose`'s transitive copy —
    // this module calls it directly, so it belongs on this module's own compile classpath.
    implementation(libs.androidx.activity.compose)

    // Renders the open source licences screen (#208) from the data `:app` generates at build
    // time. The plugin that generates it is applied to `:app` only, and this module reads the
    // result by resource name rather than by a compile-time `R.raw` reference — it cannot see
    // `:app`'s R class. See `OpenSourceLicencesScreen`.
    implementation(libs.aboutlibraries.compose.m3)

    // Health Connect (#54), for the two things only a screen can do with it: the system permission
    // dialog's `ActivityResultContract`, and the intent into Health Connect's own settings.
    // Everything that reads or writes goes through `:core:domain`'s `HealthConnect`, bound in
    // `:core:data`.
    implementation(libs.androidx.health.connect.client)
    constraints {
        // The same Guava floor `:core:data` sets, and for the same licence reason — see the note
        // there. Repeated because this module resolves its own classpath for its unit tests and
        // its bytecode transforms, and a constraint is not inherited from a sibling module.
        implementation(libs.guava) {
            because("guava before 33.4.0 depends on the MIT-licensed checker-qual")
        }
    }
}
