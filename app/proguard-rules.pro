# Keep stack traces readable after obfuscation (upload mapping.txt with each release).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room, Coil, Compose, coroutines and AndroidX ship their own consumer rules.
# Everything else in this app is reached statically (no reflection / serialization),
# so no extra keep rules are needed. Add them here if a release-only crash proves otherwise.
