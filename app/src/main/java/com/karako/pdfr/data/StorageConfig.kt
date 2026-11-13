package com.karako.pdfr.data

/**
 * Names, limits and fallbacks used by the document cache.
 *
 * These values describe the files PDFr writes next to the reader, not the
 * documents themselves. They live together because [DocumentStore] and
 * [MetadataSidecar] must agree on the directory layout and because a change to
 * a name limit should be visible in one place rather than as a number inside a
 * string helper.
 */
object StorageConfig {

    /** Cache directory under `cacheDir` that holds every imported document. */
    const val CACHE_DIR = "documents"

    /** Name used when a provider gives no usable display name. */
    const val FALLBACK_NAME = "document"

    /** Name of the staging file used while an import is being copied. */
    const val IMPORT_STAGING_NAME = "import.tmp"

    /**
     * Prefix of the throwaway directory created for a shared copy. Existing
     * share directories are cleared on the next share.
     */
    const val SHARE_DIR_PREFIX = "share-"

    /** Suffix of the sidecar that stores edited metadata for non-PDF files. */
    const val SIDECAR_SUFFIX = ".pdfr-meta"

    /** Suffix used while a sidecar is being replaced atomically. */
    const val SIDECAR_STAGING_SUFFIX = ".tmp"

    /**
     * Upper bound for a generated file name, in UTF-8 bytes. Android's
     * per-file limit is 255 bytes, but a shorter stem keeps room for the
     * extension and for providers that append their own suffixes.
     */
    const val MAX_NAME_BYTES = 160

    /** Shortest stem allowed after truncation, in UTF-8 bytes. */
    const val MIN_STEM_BYTES = 16

    /**
     * Longest path segment accepted as a fallback display name when a provider
     * does not return `DISPLAY_NAME`.
     */
    const val MAX_PATH_SEGMENT_LENGTH = 255

    /**
     * Values below this are treated as epoch seconds and multiplied by
     * [MILLIS_PER_SECOND]. Epoch milliseconds are around 1.7e12 today while
     * seconds are around 1.7e9, so the gap is wide enough to be unambiguous.
     */
    const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L

    /** Conversion used by providers that report timestamps in seconds. */
    const val MILLIS_PER_SECOND = 1000L
}
