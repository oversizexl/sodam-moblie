# UniFFI/JNA 走反射 + native 直接映射，保留类名与方法名。
-keep class uniffi.sodam_ffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
