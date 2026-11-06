/*
 * JNI bridge for qpdf.
 *
 * MuPDF can rewrite a PDF, but since version 1.28 it can no longer produce a
 * truly linearized file. qpdf does that (and much more), so PDFr links a
 * static libqpdf for each Android ABI and exposes just the operations it
 * needs. All calls arrive on the dedicated Qpdf dispatcher thread.
 */

#include <jni.h>

#include <qpdf/QPDF.hh>
#include <qpdf/QPDFWriter.hh>

#include <exception>
#include <string>

namespace {

std::string fromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring failure(JNIEnv* env, const std::string& message) {
    return env->NewStringUTF(message.empty() ? "qpdf failed" : message.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_karako_pdfr_data_Qpdf_nativeLinearize(
    JNIEnv* env,
    jobject /* thiz */,
    jstring inputPath,
    jstring outputPath,
    jstring password) {
    const std::string input = fromJava(env, inputPath);
    const std::string output = fromJava(env, outputPath);
    const std::string secret = fromJava(env, password);

    if (input.empty() || output.empty()) {
        return failure(env, "missing input or output path");
    }

    try {
        QPDF pdf;
        pdf.processFile(input.c_str(), secret.c_str());

        QPDFWriter writer(pdf, output.c_str());
        writer.setLinearization(true);
        writer.setCompressStreams(true);
        writer.setPreserveUnreferencedObjects(false);
        writer.write();

        return nullptr;
    } catch (const std::exception& e) {
        return failure(env, e.what());
    } catch (...) {
        return failure(env, "unknown qpdf error");
    }
}
