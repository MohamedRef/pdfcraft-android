# UniFFI's Kotlin bindings reach the Rust library through JNA, which finds the bindings' native
# functions and structures by reflection: keep both.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class io.github.artcraftandroid.pdfcraft.core.** { *; }
-dontwarn java.awt.**

# The diagnostics log names a failure by its exception class (Diag.failure): keep those names
# readable in release builds instead of R8's short ones (#596). Members are still shrunk.
-keepnames class * extends java.lang.Throwable
