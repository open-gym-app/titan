package com.opengym.core.domain.backup

/**
 * Where an export's bytes go, one chunk at a time.
 *
 * The whole reason [ExportWriter] streams rather than building a document and handing it over:
 * a 50,000-set history is tens of megabytes of JSON, and a `String` of it is a copy of the
 * user's entire training log in the heap of a phone that is also drawing a progress bar. A sink
 * lets the writer hold one page of rows at a time and lets the caller decide what the far end
 * is — a `Writer` over a content URI in #38, a `StringBuilder` in a test, a counter in the one
 * test that asserts nothing large is ever materialised.
 *
 * ### The contract
 *
 * - Chunks are **appended in order** and concatenate to the document. A sink that reorders,
 *   drops or rewrites them produces invalid JSON, and nothing downstream checks.
 * - A chunk is **not a JSON value**. It is whatever fragment the writer had ready, which is
 *   usually a row and a comma but may be half an object boundary. Nothing may parse one.
 * - [write] **suspends**, so a sink doing real I/O does not need a dispatcher of its own; the
 *   exporter is already off the main thread.
 * - A failure is thrown, not returned. It aborts the export at that chunk — the caller is left
 *   holding a truncated file, which is the honest outcome and why #38 writes to a scratch file
 *   and only moves it into place once the flow reports [ExportProgress.Complete].
 */
fun interface ExportSink {

    /** Appends [chunk] to the document. */
    suspend fun write(chunk: String)
}
