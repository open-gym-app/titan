package com.titan.core.domain.backup

import java.io.InputStream

/**
 * Where an import's bytes come from, one stream at a time.
 *
 * The mirror of [ExportSink], and it exists for the same reason. A 50,000-set history is tens
 * of megabytes of JSON, and a `String` of it is that document twice over — the JVM holds
 * characters in UTF-16, so a 30 MB file is 60 MB of heap before a single row has been parsed,
 * on a phone that may have 2 GB of RAM in total and no say in when the collector runs. Handing
 * the decoder a stream instead means the text is never a value at all: it arrives in 16 KiB
 * batches and is discarded as it is read, and the only thing an import holds whole is the
 * document it is building — which it has to, because the validation happens before the wipe.
 *
 * It is also why this is a source and not a stream. Nothing can hand [ExportFormat.decode] an
 * `InputStream` it has already opened, because the decoder needs two passes over the bytes: the
 * version, then the document. A type that says "opens" rather than "is open" is the difference
 * between a contract a caller can satisfy and one it discovers by getting an empty document on
 * the second read.
 *
 * ### The contract
 *
 * - [open] returns a **fresh stream over the same bytes**, positioned at the first one, and it
 *   is called more than once per import. A file, a content URI and a byte array all re-open;
 *   a socket, or a stream something else is already reading, does not and is not a document.
 * - The bytes are **UTF-8**. That is what every export writes and what the decoder assumes;
 *   a file in another encoding reads as mojibake rather than as an error.
 * - [open], and every read on what it returns, **block**. They happen on whatever context
 *   collects `ImportUseCase`'s flow, which adds no dispatcher of its own — so a collector on
 *   the main thread parses tens of megabytes on it. The choice is the caller's, exactly as it
 *   is on the export side; the backup screen collects on `Dispatchers.IO`.
 * - The bytes are **counted**: `ExportFormat.decode` reads no more than
 *   `ExportFormat.MAX_DOCUMENT_BYTES` of them, on either pass.
 * - A failure is **thrown**, not returned: an [java.io.IOException] from [open] or from a read
 *   is reported as `ImportError.IoFailure` with the database untouched, since the document is
 *   read before anything is written. Anything else is a bug and propagates.
 */
fun interface ImportSource {

    /** Opens a fresh stream over the document's UTF-8 bytes. The decoder closes what it opens. */
    fun open(): InputStream
}
