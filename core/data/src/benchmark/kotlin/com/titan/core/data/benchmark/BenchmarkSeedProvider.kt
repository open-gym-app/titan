package com.titan.core.data.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.titan.core.data.db.HistoryScales
import com.titan.core.data.db.TitanDatabase
import com.titan.core.domain.repository.UserPreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/**
 * How `:benchmark` asks the app to put a 1,000-session history behind itself.
 *
 * ### Why a `ContentProvider` and not something simpler
 *
 * The benchmark runs in its own process — it has to, or measuring a cold start would mean
 * killing the process doing the measuring — and a second process cannot write into this app's
 * data directory. So the seeding has to happen *here*, inside the app, and something has to
 * reach across the boundary and start it.
 *
 * A `ContentProvider.call` is the only cross-process entry point that is synchronous and has
 * no time limit attached. A `BroadcastReceiver` was the obvious alternative and is the wrong
 * one: `onReceive` runs on the main thread with ten seconds to finish, and seeding 50,000 rows
 * through the DAO layer takes a great deal longer than that. The call below is served on a
 * binder thread and blocks the caller — the benchmark's own test thread, which is allowed to
 * wait — until the database is ready.
 *
 * ### It exists only in the `benchmark` build type
 *
 * This file is in `src/benchmark`, so `debug` and `release` do not compile it and the merged
 * manifest of a shipping build does not declare it. An exported provider that wipes the
 * database and writes a synthetic history is exactly what must never reach a user, and the
 * build type is what guarantees it cannot: there is no flag to get wrong at runtime.
 */
class BenchmarkSeedProvider : ContentProvider() {

    /**
     * The app's own graph, rather than a second [TitanDatabase] built here.
     *
     * Two Room instances over one file would each hold their own invalidation tracker, so a
     * row written through this one would be invisible to the screens reading through the
     * other until the process restarted — which is precisely the kind of "the benchmark saw an
     * empty list" that takes a day to explain.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SeedEntryPoint {
        fun database(): TitanDatabase

        fun userPreferences(): UserPreferencesRepository
    }

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method != METHOD_SEED) return null

        val context = requireNotNull(context) { "BenchmarkSeedProvider was called before it was attached" }
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SeedEntryPoint::class.java,
        )
        val seeder = BenchmarkHistorySeeder(entryPoint.database(), entryPoint.userPreferences())

        // `runBlocking` on a binder thread, which is the whole point of this provider: the
        // caller is waiting for the database to be ready and has nothing else to do until it
        // is. Nothing on the main thread is blocked by it.
        val setCount = runBlocking { seeder.seed(HistoryScales.TARGET) }

        return Bundle().apply { putInt(RESULT_SET_COUNT, setCount) }
    }

    /*
     * The provider is a remote procedure call and nothing else. Room owns the tables; a
     * benchmark that reached them through a `Uri` would be a second data access path to keep
     * in step with the DAOs for no benefit.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /**
         * The authority suffix, appended to the app's `applicationId` — see this build type's
         * `AndroidManifest.xml`, which is where the placeholder is substituted.
         *
         * `:benchmark` builds the same string on its side of the process boundary. The two
         * copies are the contract; there is no module both can import, because this class
         * lives in a build type and the benchmark lives in a different APK.
         */
        const val AUTHORITY_SUFFIX = ".benchmark.seed"

        /** Seeds [HistoryScales.TARGET] and answers with [RESULT_SET_COUNT]. */
        const val METHOD_SEED = "seed"

        /** `workout_set` rows in the database when the call returned. */
        const val RESULT_SET_COUNT = "setCount"
    }
}
