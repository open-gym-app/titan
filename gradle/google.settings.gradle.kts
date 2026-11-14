// Google's Maven repository, for the groups Google publishes and for nothing else: AndroidX,
// the Android Gradle plugin and its tools, and Google's Android libraries. Those groups are
// asked of nobody else either, so a look-alike published to another repository is never picked
// up in their place. `com.google.*` beyond these lives on Maven Central.
//
// Applied from the `pluginManagement {}` block of both the root settings.gradle.kts and
// build-logic's, which is why it is a script and not a function: that block is compiled apart
// from the rest of its settings file and cannot see anything declared there. It adds the
// repository to plugin resolution and to dependency resolution alike, so the filter is written
// once for all four places a build looks for Google's artifacts.
listOf(pluginManagement.repositories, dependencyResolutionManagement.repositories).forEach { repositories ->
    repositories.exclusiveContent {
        forRepository { repositories.google() }
        filter {
            includeGroupByRegex("androidx\\..*")
            includeGroupByRegex("com\\.android(\\..*)?")
            includeGroupByRegex("com\\.google\\.android\\..*")
            includeGroupByRegex("com\\.google\\.testing\\.platform")
            includeGroupByRegex("com\\.google\\.prefab")
        }
    }
}
