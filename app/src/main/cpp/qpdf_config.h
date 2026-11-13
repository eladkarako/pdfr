/*
 * Central configuration for the native qpdf bridge.
 *
 * Native code cannot read Android string resources, so every value that the
 * JNI layer shares between its argument builder, its PDF/A writer and its
 * error messages lives here. Keeping them in one header means a qpdf option
 * rename, a new PDF/A identifier or a changed error wording only has to be
 * edited once.
 *
 * Naming: UPPER_SNAKE_CASE, grouped by the part of the bridge that uses it.
 */
#pragma once

#include <cstdint>
#include <string_view>

namespace qpdf_config {

// ---------------------------------------------------------------------------
// qpdf invocation
// ---------------------------------------------------------------------------

/** Program name qpdf uses for usage/error output. */
inline constexpr std::string_view PROGRAM_NAME = "qpdf";

/** Prefix qpdf puts in front of its own diagnostics. */
inline constexpr std::string_view MESSAGE_PREFIX = "pdfr";

/** Accepted range for `--jpeg-quality`; the JNI contract uses the same bounds. */
inline constexpr int JPEG_QUALITY_MIN = 0;
inline constexpr int JPEG_QUALITY_MAX = 100;

// qpdf command-line options. Prefixes that take a value end in `=`.
inline constexpr std::string_view OPT_PASSWORD = "--password=";
inline constexpr std::string_view OPT_COMPRESS_STREAMS = "--compress-streams=";
inline constexpr std::string_view OPT_DECODE_LEVEL = "--decode-level=";
inline constexpr std::string_view OPT_RECOMPRESS_FLATE = "--recompress-flate";
inline constexpr std::string_view OPT_COMPRESSION_LEVEL = "--compression-level=";
inline constexpr std::string_view OPT_OPTIMIZE_IMAGES = "--optimize-images";
inline constexpr std::string_view OPT_JPEG_QUALITY = "--jpeg-quality=";
inline constexpr std::string_view OPT_NEWLINE_BEFORE_ENDSTREAM = "--newline-before-endstream";
inline constexpr std::string_view OPT_REMOVE_UNREFERENCED_RESOURCES = "--remove-unreferenced-resources=";
inline constexpr std::string_view OPT_EXTERNALIZE_INLINE_IMAGES = "--externalize-inline-images";
inline constexpr std::string_view OPT_LINEARIZE = "--linearize";
inline constexpr std::string_view OPT_DECRYPT = "--decrypt";
inline constexpr std::string_view OPT_FORCE_VERSION = "--force-version=";

// Values passed to the options above.
inline constexpr std::string_view VALUE_YES = "yes";
inline constexpr std::string_view VALUE_NO = "no";
inline constexpr std::string_view VALUE_Y = "y";
inline constexpr std::string_view VALUE_N = "n";
inline constexpr std::string_view VALUE_DECODE_ALL = "all";
inline constexpr std::string_view VALUE_COMPRESSION_MAX = "9";
inline constexpr std::string_view VALUE_PDF_VERSION = "1.7";

// ---------------------------------------------------------------------------
// Temporary files
// ---------------------------------------------------------------------------

/** Intermediate QDF file produced by the repair cycle. */
inline constexpr std::string_view SUFFIX_QDF_PART = ".qdf.part";

/** Repaired QDF file consumed by the second repair pass. */
inline constexpr std::string_view SUFFIX_FIXED_PART = ".fixed.part";

// ---------------------------------------------------------------------------
// PDF/A-2b identification
// ---------------------------------------------------------------------------

inline constexpr std::string_view PDFA_NAMESPACE = "http://www.aiim.org/pdfa/ns/id/";
inline constexpr std::string_view PDFA_PART = "2";
inline constexpr std::string_view PDFA_CONFORMANCE = "B";
inline constexpr std::string_view PDFA_PART_ELEMENT = "pdfaid:part";
inline constexpr std::string_view PDFA_CONFORMANCE_ELEMENT = "pdfaid:conformance";

inline constexpr std::string_view RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
inline constexpr std::string_view XMP_META_NAMESPACE = "adobe:ns:meta/";
inline constexpr std::string_view RDF_CLOSING_TAG = "</rdf:RDF>";

inline constexpr std::string_view XPACKET_BEGIN =
        "<?xpacket begin=\"\xEF\xBB\xBF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n";
inline constexpr std::string_view XPACKET_END = "<?xpacket end=\"w\"?>";

// ---------------------------------------------------------------------------
// sRGB output intent and embedded ICC profile
// ---------------------------------------------------------------------------

inline constexpr std::string_view SRGB_PROFILE_NAME = "sRGB IEC61966-2.1";
inline constexpr std::string_view SRGB_COPYRIGHT = "Copyright (c) 2026 PDFr";
inline constexpr std::string_view SRGB_REGISTRY_NAME = "http://www.color.org";

// Output-intent dictionary values.
inline constexpr std::string_view OUTPUT_INTENT_TYPE = "/OutputIntent";
inline constexpr std::string_view OUTPUT_INTENT_SUBTYPE = "/GTS_PDFA1";
inline constexpr std::string_view OUTPUT_INTENT_ALTERNATE = "/DeviceRGB";

// ICC profile identity and header fields.
inline constexpr std::string_view ICC_CMM = "lcms";
inline constexpr std::uint32_t ICC_VERSION = 0x02100000; // ICC 2.1
inline constexpr std::string_view ICC_CLASS_DISPLAY = "mntr";
inline constexpr std::string_view ICC_COLOR_SPACE_RGB = "RGB ";
inline constexpr std::string_view ICC_PCS_XYZ = "XYZ ";
inline constexpr std::string_view ICC_SIGNATURE = "acsp";
inline constexpr std::string_view ICC_PLATFORM = "APPL";
inline constexpr std::string_view ICC_MANUFACTURER = "PDFr";
inline constexpr std::uint16_t ICC_YEAR = 2026;
inline constexpr std::uint16_t ICC_VERSION_MAJOR = 1;
inline constexpr std::uint16_t ICC_VERSION_MINOR = 1;

/** sRGB tone curve gamma, stored as u8Fixed8 (563 / 256 = 2.199...). */
inline constexpr std::uint16_t ICC_GAMMA_U8FIXED8 = 563;

/** D50 illuminant used by the ICC profile and its white point. */
inline constexpr double ICC_ILLUMINANT_X = 0.9642;
inline constexpr double ICC_ILLUMINANT_Y = 1.0;
inline constexpr double ICC_ILLUMINANT_Z = 0.8249;

/** Fixed sizes of the ICC header and of the per-tag padding block. */
inline constexpr std::size_t ICC_HEADER_BYTES = 128;
inline constexpr std::size_t ICC_TAG_ENTRY_BYTES = 12;

// ---------------------------------------------------------------------------
// Error messages
// ---------------------------------------------------------------------------

inline constexpr std::string_view ERROR_GENERIC = "qpdf failed";
inline constexpr std::string_view ERROR_MISSING_PATHS = "missing input or output path";
inline constexpr std::string_view ERROR_UNREADABLE_PDF = "qpdf could not read this PDF";
inline constexpr std::string_view ERROR_REPORTED = "qpdf reported an error";
inline constexpr std::string_view ERROR_UNKNOWN = "unknown qpdf error";
inline constexpr std::string_view ERROR_WRITE_QDF = "could not write the repaired QDF file";
inline constexpr std::string_view ERROR_ICC_HEADER = "ICC header is not 128 bytes";
inline constexpr std::string_view ERROR_WRITE_BINARY = "fix-qdf::writeBinary called with too many bytes";

} // namespace qpdf_config
