package com.titan.core.domain.backup

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * [delegate], refusing to hand over more than [limit] bytes.
 *
 * Every read path is counted — the single byte, the batch and the skip — because the decoder
 * reading through it is free to use any of them. Throws on the read that would cross the limit,
 * so the bytes up to it are all a refused file ever costs.
 *
 * Shared by the two readers of a file the user picked: [ExportFormat] for a Titan backup, and
 * the parsers behind [com.titan.core.domain.importer.HistoryParser] for another app's export.
 * Both are reading bytes nothing vouches for, and a cap that each kept a copy of is a cap that
 * one of them would stop counting `skip` in.
 */
internal class CappedStream(
    delegate: InputStream,
    private val limit: Long,
) : FilterInputStream(delegate) {

    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) consumed(1) }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = super.read(buffer, offset, length).also { if (it > 0) consumed(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { consumed(it) }

    override fun markSupported(): Boolean = false

    private fun consumed(bytes: Long) {
        count += bytes
        if (count > limit) throw SourceTooLargeException(limit)
    }
}

/**
 * Thrown by [CappedStream], and an [IOException] so it travels through a decoder the way a failed
 * read does. A reader catches it before the general [IOException], which is what keeps it from
 * being reported as a file that could not be read.
 */
internal class SourceTooLargeException(val limitBytes: Long) :
    IOException("the file is larger than $limitBytes bytes")
