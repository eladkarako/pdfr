cmake_minimum_required(VERSION 3.22)

# JNI bridge for the statically linked qpdf build. The qpdf source and
# libjpeg-turbo are prepared by tools/build-qpdf-android.ps1; their paths are
# passed in from that script so normal Gradle builds never need the sources.
project(pdfr_qpdf LANGUAGES CXX)

if(NOT QPDF_SOURCE_DIR OR NOT QPDF_BUILD_DIR OR NOT QPDF_LIB OR NOT JPEG_LIB)
    message(FATAL_ERROR "QPDF_SOURCE_DIR, QPDF_BUILD_DIR, QPDF_LIB and JPEG_LIB are required")
endif()

add_library(qpdfjni SHARED qpdf_jni.cpp)

target_compile_features(qpdfjni PRIVATE cxx_std_20)
target_include_directories(
    qpdfjni
    PRIVATE
        "${QPDF_SOURCE_DIR}/include"
        "${QPDF_BUILD_DIR}/libqpdf"
)
target_link_libraries(qpdfjni PRIVATE "${QPDF_LIB}" "${JPEG_LIB}" z)
target_compile_options(qpdfjni PRIVATE -fvisibility=hidden -fexceptions)
