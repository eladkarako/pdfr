# Pdf-Viewer relies on AndroidX lifecycle classes that R8 can strip when a
# Java consumer resolves older lifecycle-process versions. The library ships
# its own consumer rules; these are the belt-and-braces copies from its README.
-keep,allowobfuscation class androidx.lifecycle.ProcessLifecycleOwner { *; }
-keep,allowobfuscation class androidx.lifecycle.ProcessLifecycleOwner$* { *; }

# PdfBox-Android can decode JPEG 2000 only when the optional gemalto JP2
# library is present. Leaf does not ship it, and Android's PdfRenderer does the
# actual page drawing, so the references are harmless.
-dontwarn com.gemalto.jp2.**

# Public-key (certificate) security is the only code path that needs
# BouncyCastle. Leaf uses the standard password handler, which runs on plain
# JCE. Keeping the warning quiet avoids pulling in ~10 MB of crypto jars.
-dontwarn org.bouncycastle.**

# I do not need to obfuscate or rename the classes since this is an opensource code, I want to make reverse engineering from APK to source easier for inspection.
-dontobfuscate
