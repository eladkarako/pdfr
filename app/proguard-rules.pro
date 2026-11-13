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

# Keep generic signatures, nested-class links and checked exceptions in the
# dex. Without these attributes a decompiler sees erased types and synthetic
# names, which makes the un-obfuscated build much harder to read. The cost is a
# few kilobytes, not runtime performance.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions

# Keep source file names and line numbers so stack traces and decompiled
# output point back to the original Kotlin file. This is a deliberate
# readability trade-off; remove these two attributes if APK size matters more
# than crash diagnostics.
-keepattributes SourceFile,LineNumberTable
