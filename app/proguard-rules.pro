# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**

# JNI libraries look up Java/Kotlin classes, fields and methods by name: R8 must not strip or rename them
# (1.2.4: "Failed to get field ID for blankPenalty" — unused-in-Kotlin config fields were removed)
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class helium314.keyboard.latin.utils.JniUtils { *; }
-keep class helium314.keyboard.latin.BinaryDictionary { *; }
-keep class helium314.keyboard.latin.DicTraverseSession { *; }
-keep class helium314.keyboard.latin.makedict.** { *; }
-keep class helium314.keyboard.keyboard.ProximityInfo { *; }
-keep class com.android.inputmethod.** { *; }
