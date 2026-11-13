/*
 * JNI bridge for qpdf.
 *
 * MuPDF can rewrite a PDF, but since version 1.28 it can no longer produce a
 * truly linearized file. qpdf does that (and much more), so PDFr links a
 * static libqpdf for each Android ABI and exposes just the operations it
 * needs. All calls arrive on the dedicated Qpdf dispatcher thread.
 *
 * The repair action follows qpdf's documented fix-qdf cycle: write the PDF in
 * QDF form, repair the stream lengths and cross-reference table, then rewrite
 * it as a normal PDF. The QdfFixer below is a port of qpdf's fix-qdf.cc; it
 * uses libqpdf for the cross-reference entry helpers and never runs a separate
 * executable.
 */

#include <jni.h>

#include <qpdf/QPDF.hh>
#include <qpdf/QPDFJob.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFWriter.hh>
#include <qpdf/QPDFXRefEntry.hh>
#include <qpdf/QIntC.hh>
#include <qpdf/QUtil.hh>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <exception>
#include <fstream>
#include <memory>
#include <regex>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include "qpdf_config.h"

namespace {

    using namespace qpdf_config;

    std::string fromJava(JNIEnv *env, jstring value) {
        if (value == nullptr) {
            return {};
        }
        const char *chars = env->GetStringUTFChars(value, nullptr);
        if (chars == nullptr) {
            return {};
        }
        std::string result(chars);
        env->ReleaseStringUTFChars(value, chars);
        return result;
    }

    jstring failure(JNIEnv *env, const std::string &message) {
        return env->NewStringUTF(message.empty() ? ERROR_GENERIC.data() : message.c_str());
    }

    /**
     * Repairs a PDF that is in QDF form, updating stream lengths and the
     * cross-reference table. Ported from qpdf's fix-qdf.cc, with the process
     * exit replaced by an exception so a bad file cannot kill the app.
     */
    class QdfFixer {
    public:
        QdfFixer(std::string const &filename, std::ostream &out);

        ~QdfFixer() = default;

        void processLines(std::string const &input);

    private:
        void fatal(std::string const &);

        void checkObjId(std::string const &obj_id);

        void adjustOstreamXref();

        void writeOstream();

        void writeBinary(unsigned long long val, size_t bytes);

        std::string filename;
        std::ostream &out;
        enum {
            st_top,
            st_in_obj,
            st_in_stream,
            st_after_stream,
            st_in_ostream_dict,
            st_in_ostream_offsets,
            st_in_ostream_outer,
            st_in_ostream_obj,
            st_in_xref_stream_dict,
            st_in_length,
            st_at_xref,
            st_before_trailer,
            st_in_trailer,
            st_done,
        } state{st_top};

        size_t lineno{0};
        qpdf_offset_t offset{0};
        qpdf_offset_t last_offset{0};
        int last_obj{0};
        std::vector <QPDFXRefEntry> xref;
        qpdf_offset_t stream_start{0};
        size_t stream_length{0};
        qpdf_offset_t xref_offset{0};
        size_t xref_f1_nbytes{0};
        size_t xref_f2_nbytes{0};
        size_t xref_size{0};
        std::vector <std::string_view> ostream;
        std::vector <qpdf_offset_t> ostream_offsets;
        std::vector <std::string_view> ostream_discarded;
        size_t ostream_idx{0};
        int ostream_id{0};
        std::string ostream_extends;
    };

    QdfFixer::QdfFixer(std::string const &filename, std::ostream &out) :
            filename(filename),
            out(out) {
    }

    void QdfFixer::fatal(std::string const &msg) {
        throw std::runtime_error(msg);
    }

    void QdfFixer::processLines(std::string const &input) {
        using namespace std::literals;

        static const std::regex re_n_0_obj("^(\\d+) 0 obj\n$");
        static const std::regex re_extends("/Extends (\\d+ 0 R)");
        static const std::regex re_ostream_obj("^%% Object stream: object (\\d+)");
        static const std::regex re_num("^\\d+\n$");
        static const std::regex re_size_n("^  /Size \\d+\n$");

        auto sv_diff = [](size_t i) { return static_cast<std::string_view::difference_type>(i); };

        lineno = 0;
        bool more = true;
        auto len_line = sv_diff(0);

        std::string_view line;
        std::string_view input_view{input.data(), input.size()};
        size_t offs = 0;

        auto b_line = input.cbegin();
        std::smatch m;
        auto const matches = [&m, &b_line, &len_line](std::regex const &r) {
            return std::regex_search(b_line, b_line + len_line, m, r);
        };

        while (more) {
            ++lineno;
            last_offset = offset;
            b_line += len_line;

            offs = input_view.find('\n');
            if (offs == std::string::npos) {
                more = false;
                line = input_view;
            } else {
                offs++;
                line = input_view.substr(0, offs);
                input_view.remove_prefix(offs);
            }
            len_line = sv_diff(line.size());
            offset += len_line;

            if (state == st_top) {
                if (matches(re_n_0_obj)) {
                    checkObjId(m[1].str());
                    state = st_in_obj;
                } else if (line.compare("xref\n"sv) == 0) {
                    xref_offset = last_offset;
                    state = st_at_xref;
                }
                out << line;
            } else if (state == st_in_obj) {
                out << line;
                if (line.compare("stream\n"sv) == 0) {
                    state = st_in_stream;
                    stream_start = offset;
                } else if (line.compare("endobj\n"sv) == 0) {
                    state = st_top;
                } else if (line.find("/Type /ObjStm"sv) != line.npos) {
                    state = st_in_ostream_dict;
                    ostream_id = last_obj;
                } else if (line.find("/Type /XRef"sv) != line.npos) {
                    xref_offset = xref.back().getOffset();
                    xref_f1_nbytes = 0;
                    auto t = xref_offset;
                    while (t) {
                        t >>= 8;
                        ++xref_f1_nbytes;
                    }
                    // Figure out how many bytes we need for ostream
                    // index. Make sure we get at least 1 byte even if
                    // there are no object streams.
                    int max_objects = 1;
                    for (auto const &e: xref) {
                        if ((e.getType() == 2) && (e.getObjStreamIndex() > max_objects)) {
                            max_objects = e.getObjStreamIndex();
                        }
                    }
                    while (max_objects) {
                        max_objects >>= 8;
                        ++xref_f2_nbytes;
                    }
                    auto esize = 1 + xref_f1_nbytes + xref_f2_nbytes;
                    xref_size = 1 + xref.size();
                    auto length = xref_size * esize;
                    out << "  /Length " << length << "\n"
                        << "  /W [ 1 " << xref_f1_nbytes << " " << xref_f2_nbytes << " ]\n";
                    state = st_in_xref_stream_dict;
                }
            } else if (state == st_in_ostream_dict) {
                if (line.compare("stream\n"sv) == 0) {
                    state = st_in_ostream_offsets;
                } else {
                    ostream_discarded.push_back(line);
                    if (matches(re_extends)) {
                        ostream_extends = m[1].str();
                    }
                }
                // discard line
            } else if (state == st_in_ostream_offsets) {
                if (matches(re_ostream_obj)) {
                    checkObjId(m[1].str());
                    stream_start = last_offset;
                    state = st_in_ostream_outer;
                    ostream.push_back(line);
                } else {
                    ostream_discarded.push_back(line);
                }
                // discard line
            } else if (state == st_in_ostream_outer) {
                adjustOstreamXref();
                ostream_offsets.push_back(last_offset - stream_start);
                state = st_in_ostream_obj;
                ostream.push_back(line);
            } else if (state == st_in_ostream_obj) {
                ostream.push_back(line);
                if (matches(re_ostream_obj)) {
                    checkObjId(m[1].str());
                    state = st_in_ostream_outer;
                } else if (line.compare("endstream\n"sv) == 0) {
                    stream_length = QIntC::to_size(last_offset - stream_start);
                    writeOstream();
                    state = st_in_obj;
                }
            } else if (state == st_in_xref_stream_dict) {
                if ((line.find("/Length"sv) != line.npos) || (line.find("/W"sv) != line.npos)) {
                    // already printed
                } else if (line.find("/Size"sv) != line.npos) {
                    auto size = 1 + xref.size();
                    out << "  /Size " << size << "\n";
                } else {
                    out << line;
                }
                if (line.compare("stream\n"sv) == 0) {
                    writeBinary(0, 1);
                    writeBinary(0, xref_f1_nbytes);
                    writeBinary(0, xref_f2_nbytes);
                    for (auto const &x: xref) {
                        unsigned long long f1 = 0;
                        unsigned long long f2 = 0;
                        unsigned int type = QIntC::to_uint(x.getType());
                        if (1 == type) {
                            f1 = QIntC::to_ulonglong(x.getOffset());
                        } else {
                            f1 = QIntC::to_ulonglong(x.getObjStreamNumber());
                            f2 = QIntC::to_ulonglong(x.getObjStreamIndex());
                        }
                        writeBinary(type, 1);
                        writeBinary(f1, xref_f1_nbytes);
                        writeBinary(f2, xref_f2_nbytes);
                    }
                    out << "\nendstream\nendobj\n\n"
                        << "startxref\n"
                        << xref_offset << "\n%%EOF\n";
                    state = st_done;
                }
            } else if (state == st_in_stream) {
                if (line.compare("endstream\n"sv) == 0) {
                    stream_length = QIntC::to_size(last_offset - stream_start);
                    state = st_after_stream;
                }
                out << line;
            } else if (state == st_after_stream) {
                if (line.compare("%QDF: ignore_newline\n"sv) == 0) {
                    if (stream_length > 0) {
                        --stream_length;
                    }
                } else if (matches(re_n_0_obj)) {
                    checkObjId(m[1].str());
                    state = st_in_length;
                }
                out << line;
            } else if (state == st_in_length) {
                if (!matches(re_num)) {
                    fatal(filename + ":" + std::to_string(lineno) + ": expected integer");
                }
                std::string new_length = std::to_string(stream_length) + "\n";
                offset -= QIntC::to_offset(line.length());
                offset += QIntC::to_offset(new_length.length());
                out << new_length;
                state = st_top;
            } else if (state == st_at_xref) {
                auto n = xref.size();
                out << "0 " << 1 + n << "\n0000000000 65535 f \n";
                for (auto const &e: xref) {
                    out << QUtil::int_to_string(e.getOffset(), 10) << " 00000 n \n";
                }
                state = st_before_trailer;
            } else if (state == st_before_trailer) {
                if (line.compare("trailer <<\n"sv) == 0) {
                    out << line;
                    state = st_in_trailer;
                }
                // no output
            } else if (state == st_in_trailer) {
                if (matches(re_size_n)) {
                    out << "  /Size " << 1 + xref.size() << "\n";
                } else {
                    out << line;
                }
                if (line.compare(">>\n"sv) == 0) {
                    out << "startxref\n" << xref_offset << "\n%%EOF\n";
                    state = st_done;
                }
            } else if (state == st_done) {
                // ignore
            }
        }
    }

    void QdfFixer::checkObjId(std::string const &cur_obj_str) {
        if (std::stoi(cur_obj_str) != ++last_obj) {
            fatal(
                    filename + ":" + std::to_string(lineno) + ": expected object " +
                    std::to_string(last_obj));
        }
        xref.push_back(QPDFXRefEntry(1, last_offset, 0));
    }

    void QdfFixer::adjustOstreamXref() {
        xref.back() = QPDFXRefEntry(2, ostream_id, QIntC::to_int(ostream_idx++));
    }

    void QdfFixer::writeOstream() {
        auto first = ostream_offsets.at(0);
        auto onum = ostream_id;
        std::string offsets;
        auto n = ostream_offsets.size();
        for (auto iter: ostream_offsets) {
            iter -= QIntC::to_offset(first);
            ++onum;
            offsets += std::to_string(onum) + " " + std::to_string(iter) + "\n";
        }
        auto offset_adjust = QIntC::to_offset(offsets.size());
        first += offset_adjust;
        stream_length += QIntC::to_size(offset_adjust);
        std::string dict_data = "";
        dict_data += "  /Length " + std::to_string(stream_length) + "\n";
        dict_data += "  /N " + std::to_string(n) + "\n";
        dict_data += "  /First " + std::to_string(first) + "\n";
        if (!ostream_extends.empty()) {
            dict_data += "  /Extends " + ostream_extends + "\n";
        }
        dict_data += ">>\n";
        offset_adjust += QIntC::to_offset(dict_data.length());
        out << dict_data << "stream\n" << offsets;
        for (auto const &o: ostream) {
            out << o;
        }

        for (auto const &o: ostream_discarded) {
            offset -= QIntC::to_offset(o.length());
        }
        offset += offset_adjust;

        ostream_idx = 0;
        ostream_id = 0;
        ostream.clear();
        ostream_offsets.clear();
        ostream_discarded.clear();
        ostream_extends.clear();
    }

    void QdfFixer::writeBinary(unsigned long long val, size_t bytes) {
        if (bytes > sizeof(unsigned long long)) {
            throw std::logic_error(std::string(ERROR_WRITE_BINARY));
        }
        std::string data(bytes, '\0');
        for (auto i = bytes; i > 0; --i) {
            data[i - 1] = static_cast<char>(val & 0xff); // i.e. val % 256
            val >>= 8;                                   // i.e. val = val / 256
        }
        out << data;
    }

    // ------------------------------------------------------------------
    // Best-effort PDF/A-2b support
    //
    // qpdf can preserve PDF/A but cannot create it. For the Normalize
    // action we add the two pieces qpdf does not provide: a PDF/A XMP
    // identification block and an sRGB output intent with an embedded ICC
    // profile. Fonts and page content are left untouched.
    // ------------------------------------------------------------------

    void appendU16(std::string &out, unsigned value) {
        out.push_back(static_cast<char>((value >> 8) & 0xff));
        out.push_back(static_cast<char>(value & 0xff));
    }

    void appendU32(std::string &out, std::uint32_t value) {
        out.push_back(static_cast<char>((value >> 24) & 0xff));
        out.push_back(static_cast<char>((value >> 16) & 0xff));
        out.push_back(static_cast<char>((value >> 8) & 0xff));
        out.push_back(static_cast<char>(value & 0xff));
    }

    void appendS15Fixed16(std::string &out, double value) {
        auto fixed = static_cast<std::int32_t>(std::llround(value * 65536.0));
        appendU32(out, static_cast<std::uint32_t>(fixed));
    }

    std::string iccDescTag(std::string const &text) {
        std::string tag;
        tag.append("desc", 4);
        appendU32(tag, 0);
        appendU32(tag, static_cast<std::uint32_t>(text.size() + 1));
        tag += text;
        tag.push_back('\0');
        appendU32(tag, 0); // Unicode language code
        appendU32(tag, 0); // Unicode count
        appendU16(tag, 0); // ScriptCode code
        tag.push_back('\0'); // ScriptCode count
        tag.append(67, '\0'); // ScriptCode data
        return tag;
    }

    std::string iccTextTag(std::string const &text) {
        std::string tag;
        tag.append("text", 4);
        appendU32(tag, 0);
        tag += text;
        tag.push_back('\0');
        return tag;
    }

    std::string iccXyzTag(double x, double y, double z) {
        std::string tag;
        tag.append("XYZ ", 4);
        appendU32(tag, 0);
        appendS15Fixed16(tag, x);
        appendS15Fixed16(tag, y);
        appendS15Fixed16(tag, z);
        return tag;
    }

    std::string iccCurveTag() {
        std::string tag;
        tag.append("curv", 4);
        appendU32(tag, 0);
        appendU32(tag, 1); // one entry: a gamma value
        appendU16(tag, ICC_GAMMA_U8FIXED8); // 2.19921875 in u8Fixed8 notation
        return tag;
    }

    /**
     * Builds a compact matrix/TRC sRGB profile. The primaries are adapted to
     * D50 as required by ICC, and the tone curves use gamma 2.2 (the usual
     * approximation of the sRGB transfer function).
     */
    std::string srgbProfile() {
        struct Tag {
            char const *name;
            std::string data;
        };

        const std::vector <Tag> tags = {
                {"desc", iccDescTag(std::string(SRGB_PROFILE_NAME))},
                {"cprt", iccTextTag(std::string(SRGB_COPYRIGHT))},
                {"wtpt", iccXyzTag(ICC_ILLUMINANT_X, ICC_ILLUMINANT_Y, ICC_ILLUMINANT_Z)},
                {"rXYZ", iccXyzTag(0.4360747, 0.2225045, 0.0139322)},
                {"gXYZ", iccXyzTag(0.3850649, 0.7168786, 0.0971045)},
                {"bXYZ", iccXyzTag(0.1430804, 0.0606169, 0.7141733)},
                {"rTRC", iccCurveTag()},
                {"gTRC", iccCurveTag()},
                {"bTRC", iccCurveTag()},
        };

        const auto padding = [](size_t size) { return (size + 3) & ~size_t(3); };

        size_t offset = ICC_HEADER_BYTES + 4 + tags.size() * ICC_TAG_ENTRY_BYTES;
        std::string table;
        appendU32(table, static_cast<std::uint32_t>(tags.size()));
        for (auto const &tag: tags) {
            table.append(tag.name, 4);
            appendU32(table, static_cast<std::uint32_t>(offset));
            appendU32(table, static_cast<std::uint32_t>(tag.data.size()));
            offset += padding(tag.data.size());
        }

        std::string body;
        for (auto const &tag: tags) {
            body += tag.data;
            while (body.size() % 4 != 0) {
                body.push_back('\0');
            }
        }

        std::string header;
        appendU32(
                header,
                static_cast<std::uint32_t>(ICC_HEADER_BYTES + table.size() + body.size()));
        header += ICC_CMM; // CMM type
        appendU32(header, ICC_VERSION); // ICC 2.1
        header += ICC_CLASS_DISPLAY; // display profile
        header += ICC_COLOR_SPACE_RGB; // data colour space
        header += ICC_PCS_XYZ; // PCS
        appendU16(header, ICC_YEAR);
        appendU16(header, ICC_VERSION_MAJOR);
        appendU16(header, ICC_VERSION_MINOR);
        appendU16(header, 0);
        appendU16(header, 0);
        appendU16(header, 0);
        header += ICC_SIGNATURE;
        header += ICC_PLATFORM;
        appendU32(header, 0); // flags
        appendU32(header, 0); // manufacturer
        appendU32(header, 0); // model
        appendU32(header, 0); // attributes
        appendU32(header, 0);
        appendU32(header, 0); // rendering intent
        appendS15Fixed16(header, ICC_ILLUMINANT_X); // D50 illuminant
        appendS15Fixed16(header, ICC_ILLUMINANT_Y);
        appendS15Fixed16(header, ICC_ILLUMINANT_Z);
        header += ICC_MANUFACTURER;
        header.append(16, '\0'); // profile ID
        header.append(28, '\0'); // reserved

        if (header.size() != ICC_HEADER_BYTES) {
            throw std::logic_error(std::string(ERROR_ICC_HEADER));
        }
        return header + table + body;
    }

    /** Removes any pdfaid:part/conformance pair and inserts a PDF/A-2B one. */
    std::string withPdfAIdentification(std::string const &existing) {
        static const std::string description =
                "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"" +
                std::string(PDFA_NAMESPACE) + "\">" +
                "<" + std::string(PDFA_PART_ELEMENT) + ">" + std::string(PDFA_PART) +
                "</" + std::string(PDFA_PART_ELEMENT) + ">" +
                "<" + std::string(PDFA_CONFORMANCE_ELEMENT) + ">" +
                std::string(PDFA_CONFORMANCE) +
                "</" + std::string(PDFA_CONFORMANCE_ELEMENT) + ">" +
                "</rdf:Description>";

        std::string xmp = existing;
        auto eraseElement = [&xmp](std::string const &name) {
            auto open = xmp.find("<" + name);
            while (open != std::string::npos) {
                auto close = xmp.find("</" + name + ">", open);
                if (close != std::string::npos) {
                    xmp.erase(open, close + name.size() + 3 - open);
                } else {
                    auto end = xmp.find('>', open);
                    if (end == std::string::npos) {
                        break;
                    }
                    xmp.erase(open, end - open + 1);
                }
                open = xmp.find("<" + name, open);
            }
        };

        eraseElement(std::string(PDFA_PART_ELEMENT));
        eraseElement(std::string(PDFA_CONFORMANCE_ELEMENT));

        auto rdfEnd = xmp.rfind(std::string(RDF_CLOSING_TAG));
        if (rdfEnd != std::string::npos) {
            xmp.insert(rdfEnd, description);
            return xmp;
        }

        std::string packet;
        packet += XPACKET_BEGIN;
        packet += "<x:xmpmeta xmlns:x=\"";
        packet += XMP_META_NAMESPACE;
        packet += "\">\n<rdf:RDF xmlns:rdf=\"";
        packet += RDF_NAMESPACE;
        packet += "\">\n";
        packet += description;
        packet += "\n</rdf:RDF>\n</x:xmpmeta>\n";
        packet += XPACKET_END;
        return packet;
    }

    void applyPdfA(QPDF &pdf) {
        auto root = pdf.getRoot();

        auto intents = root.getKey("/OutputIntents");
        if (!(intents.isArray() && intents.getArrayNItems() > 0)) {
            auto profile = QPDFObjectHandle::newStream(&pdf, srgbProfile());
            auto profileDict = profile.getDict();
            profileDict.replaceKey("/N", QPDFObjectHandle::newInteger(3));
            profileDict.replaceKey(
                    "/Alternate",
                    QPDFObjectHandle::newName(std::string(OUTPUT_INTENT_ALTERNATE)));

            auto intent = QPDFObjectHandle::newDictionary();
            intent.replaceKey(
                    "/Type",
                    QPDFObjectHandle::newName(std::string(OUTPUT_INTENT_TYPE)));
            intent.replaceKey(
                    "/S",
                    QPDFObjectHandle::newName(std::string(OUTPUT_INTENT_SUBTYPE)));
            intent.replaceKey(
                    "/OutputConditionIdentifier",
                    QPDFObjectHandle::newString(std::string(SRGB_PROFILE_NAME)));
            intent.replaceKey(
                    "/RegistryName",
                    QPDFObjectHandle::newString(std::string(SRGB_REGISTRY_NAME)));
            intent.replaceKey(
                    "/Info",
                    QPDFObjectHandle::newString(std::string(SRGB_PROFILE_NAME)));
            intent.replaceKey("/DestOutputProfile", profile);
            root.replaceKey("/OutputIntents", QPDFObjectHandle::newArray({intent}));
        }

        auto metadata = root.getKey("/Metadata");
        std::string existing;
        if (metadata.isStream()) {
            try {
                auto data = metadata.getStreamData(qpdf_dl_all);
                if (data && data->getSize() > 0) {
                    existing.assign(
                            reinterpret_cast<char const *>(data->getBuffer()),
                            data->getSize());
                }
            } catch (...) {
                // A metadata stream with an unsupported filter is replaced
                // below rather than failing the whole Normalize operation.
                existing.clear();
            }
        }

        auto updated = withPdfAIdentification(existing);
        if (metadata.isStream()) {
            metadata.replaceStreamData(
                    updated,
                    QPDFObjectHandle::newNull(),
                    QPDFObjectHandle::newNull());
            auto dict = metadata.getDict();
            dict.replaceKey("/Type", QPDFObjectHandle::newName("/Metadata"));
            dict.replaceKey("/Subtype", QPDFObjectHandle::newName("/XML"));
        } else {
            auto stream = QPDFObjectHandle::newStream(&pdf, updated);
            auto dict = stream.getDict();
            dict.replaceKey("/Type", QPDFObjectHandle::newName("/Metadata"));
            dict.replaceKey("/Subtype", QPDFObjectHandle::newName("/XML"));
            root.replaceKey("/Metadata", stream);
        }
    }

}  // namespace

extern "C" JNIEXPORT jstring

JNICALL
Java_com_karako_pdfr_data_Qpdf_nativeNormalize(
        JNIEnv *env,
        jobject /* thiz */,
        jstring inputPath,
        jstring outputPath,
        jstring password,
        jboolean compressStreams,
        jboolean decodeAllStreams,
        jboolean recompressFlate,
        jboolean compressionLevel9,
        jint jpegQuality,
        jboolean optimizeImages,
        jboolean newlineBeforeEndstream,
        jboolean removeUnreferencedResources,
        jboolean externalizeInlineImages,
        jboolean linearize,
        jboolean pdfA) {
    const std::string input = fromJava(env, inputPath);
    const std::string output = fromJava(env, outputPath);
    const std::string secret = fromJava(env, password);

    if (input.empty() || output.empty()) {
        return failure(env, std::string(ERROR_MISSING_PATHS));
    }

    std::vector <std::string> args;
    args.emplace_back(PROGRAM_NAME);
    args.push_back(input);
    args.push_back(output);
    if (!secret.empty()) {
        args.push_back(std::string(OPT_PASSWORD) + secret);
    }

    // qpdf applies these in one pass. Its internal ordering already puts
    // decoding before recompression and image optimization before output.
    args.push_back(
            std::string(OPT_COMPRESS_STREAMS) +
            std::string(compressStreams ? VALUE_Y : VALUE_N));
    if (decodeAllStreams) {
        args.push_back(std::string(OPT_DECODE_LEVEL) + std::string(VALUE_DECODE_ALL));
    }
    if (recompressFlate) {
        args.emplace_back(OPT_RECOMPRESS_FLATE);
    }
    if (compressionLevel9) {
        args.push_back(
                std::string(OPT_COMPRESSION_LEVEL) + std::string(VALUE_COMPRESSION_MAX));
    }
    if (optimizeImages) {
        args.emplace_back(OPT_OPTIMIZE_IMAGES);
        if (jpegQuality >= JPEG_QUALITY_MIN && jpegQuality <= JPEG_QUALITY_MAX) {
            args.push_back(std::string(OPT_JPEG_QUALITY) + std::to_string(jpegQuality));
        }
    }
    if (newlineBeforeEndstream || pdfA) {
        args.emplace_back(OPT_NEWLINE_BEFORE_ENDSTREAM);
    }
    args.push_back(
            std::string(OPT_REMOVE_UNREFERENCED_RESOURCES) +
            std::string(removeUnreferencedResources ? VALUE_YES : VALUE_NO));
    if (externalizeInlineImages) {
        args.emplace_back(OPT_EXTERNALIZE_INLINE_IMAGES);
    }
    if (linearize) {
        args.emplace_back(OPT_LINEARIZE);
    }
    if (pdfA) {
        // PDF/A-2 forbids encryption and is based on PDF 1.7. The qpdf writer
        // preserves encryption by default, so ask it to decrypt first.
        args.emplace_back(OPT_DECRYPT);
        args.push_back(std::string(OPT_FORCE_VERSION) + std::string(VALUE_PDF_VERSION));
    }

    // initializeFromArgv may reorder or otherwise touch the argument array,
    // so give it writable, NUL-terminated copies rather than string internals.
    std::vector <std::vector<char>> buffers;
    buffers.reserve(args.size());
    for (auto const &arg: args) {
        buffers.emplace_back(arg.c_str(), arg.c_str() + arg.size() + 1);
    }
    std::vector<char *> argv;
    argv.reserve(buffers.size() + 1);
    for (auto &buffer: buffers) {
        argv.push_back(buffer.data());
    }
    argv.push_back(nullptr);

    try {
        QPDFJob job;
        job.setMessagePrefix(std::string(MESSAGE_PREFIX));
        job.initializeFromArgv(argv.data());

        auto pdf = job.createQPDF();
        if (!pdf) {
            return failure(env, std::string(ERROR_UNREADABLE_PDF));
        }
        if (pdfA) {
            applyPdfA(*pdf);
        }
        job.writeQPDF(*pdf);

        if (job.getExitCode() == QPDFJob::EXIT_ERROR) {
            return failure(env, std::string(ERROR_REPORTED));
        }
        return nullptr;
    } catch (const std::exception &e) {
        return failure(env, e.what());
    } catch (...) {
        return failure(env, std::string(ERROR_UNKNOWN));
    }
}

extern "C" JNIEXPORT jstring

JNICALL
Java_com_karako_pdfr_data_Qpdf_nativeFixPdf(
        JNIEnv *env,
        jobject /* thiz */,
        jstring inputPath,
        jstring outputPath,
        jstring password) {
    const std::string input = fromJava(env, inputPath);
    const std::string output = fromJava(env, outputPath);
    const std::string secret = fromJava(env, password);

    if (input.empty() || output.empty()) {
        return failure(env, std::string(ERROR_MISSING_PATHS));
    }

    const std::string qdf = output + std::string(SUFFIX_QDF_PART);
    const std::string fixed = output + std::string(SUFFIX_FIXED_PART);
    auto cleanup = [&qdf, &fixed]() {
        std::remove(qdf.c_str());
        std::remove(fixed.c_str());
    };

    try {
        QPDF pdf;
        pdf.processFile(input.c_str(), secret.c_str());
        {
            QPDFWriter writer(pdf, qdf.c_str());
            writer.setQDFMode(true);
            writer.setCompressStreams(false);
            writer.setPreserveUnreferencedObjects(false);
            writer.write();
        }

        const std::string qdf_data = QUtil::read_file_into_string(qdf.c_str());
        {
            std::ofstream repaired(fixed, std::ios::binary);
            if (repaired.fail()) {
                throw std::runtime_error(std::string(ERROR_WRITE_QDF));
            }
            QdfFixer fixer(qdf, repaired);
            fixer.processLines(qdf_data);
        }

        {
            QPDF repaired;
            repaired.processFile(fixed.c_str());
            QPDFWriter writer(repaired, output.c_str());
            writer.setCompressStreams(true);
            writer.setPreserveUnreferencedObjects(false);
            writer.write();
        }

        cleanup();
        return nullptr;
    } catch (const std::exception &e) {
        cleanup();
        return failure(env, e.what());
    } catch (...) {
        cleanup();
        return failure(env, std::string(ERROR_UNKNOWN));
    }
}
