# MuPDF's Java bindings are called from native code by class, field and method
# name/signature. Renaming or stripping any of them would break the JNI bridge.
-keep class com.artifex.mupdf.fitz.** { *; }
-keepclassmembers class com.artifex.mupdf.fitz.** {
    native <methods>;
}

# MuPDF loads its native library by name and uses reflection for a few helpers.
-dontwarn com.artifex.mupdf.fitz.**

# qpdf's JNI symbol is bound by the class and method name
# (Java_com_karako_pdfr_data_Qpdf_nativeLinearize), so keep both.
-keepclasseswithmembernames class com.karako.pdfr.data.Qpdf {
    native <methods>;
}

# I do not need to obfuscate or rename the classes since this is an opensource code, I want to make reverse engineering from APK to source easier for inspection.
-dontobfuscate
