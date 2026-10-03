package com.titan.core.domain.repository

import com.titan.core.domain.model.DefaultTemplate

/**
 * The templates the app ships with, read from where they are defined.
 *
 * An interface here because the definitions live in `:core:data`, beside the seed that
 * inserts them when the database is created: their names are string resources and their
 * exercises are references into the seeded catalogue, and neither is something a pure Kotlin
 * module can read. The domain only needs the result, to put back the defaults a user deleted.
 */
interface DefaultTemplateCatalogue {

    /**
     * Every default, in the order the app defines them, named in the device's current
     * language and with every exercise resolved to its id in this database.
     *
     * A default whose exercises do not all resolve is left out rather than returned with a
     * gap in it, matching what the seed does with one: an unresolvable reference is a build
     * mistake, and the seed's own test is what keeps one from shipping.
     */
    suspend fun getAll(): List<DefaultTemplate>
}
