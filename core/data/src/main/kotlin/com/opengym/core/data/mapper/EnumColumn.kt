package com.opengym.core.data.mapper

/**
 * Reading enum-valued columns back into the domain's enums.
 *
 * The schema stores these as `TEXT` holding the constant's `name` (see
 * PERSISTENCE.md) because SQLite cannot express an enumerated type and an ordinal
 * would silently renumber every stored row the day a constant is inserted in the
 * middle. The cost of that choice is that nothing in the database stops a column
 * from holding a name this build does not have — a row written by a newer version,
 * an import, or a value dropped from the enum since — so every read has to decide
 * what to do with one, and [decodeEnum] is where that decision is made once.
 */

/**
 * [name] as a member of [T], or **null** when it is absent or names nothing this
 * build knows.
 *
 * Null rather than a throw, because the alternative is that one unrecognised
 * string makes a whole exercise unreadable. Callers supply the fallback that suits
 * the column: a nullable field takes the null as-is, a non-null one falls back to
 * the same default its column declares.
 */
internal inline fun <reified T : Enum<T>> decodeEnum(name: String?): T? =
    name?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } }
