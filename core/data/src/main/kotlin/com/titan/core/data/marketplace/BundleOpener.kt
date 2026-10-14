package com.titan.core.data.marketplace

import android.content.Context
import java.io.IOException
import javax.inject.Inject

/** Reads a bundle's text from wherever bundles live. A seam so tests need not stage real assets. */
fun interface BundleOpener {

    /** @throws IOException when there is nothing at [path]. */
    fun read(path: String): String
}

/** Reads from the APK's assets. */
class AssetBundleOpener @Inject constructor(private val context: Context) : BundleOpener {

    override fun read(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
