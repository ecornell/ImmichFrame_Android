# R8 rules for ImmichFrame. Classes named in the manifest and layouts are kept by the rules AAPT
# generates, and Retrofit, Gson and OkHttp ship their own consumer rules. What remains is what
# this app reaches by reflection.

# Gson reads and writes these by field name: the Retrofit responses (RandomImageAndInfo, Config,
# Weather) and the cached server settings (CachedServerSettingsStore). Their constructors stay
# too, so R8's full mode cannot assume they are never instantiated and fold their fields to null.
-keep class com.immichframe.immichframe.Helpers$ImageResponse { *; }
-keep class com.immichframe.immichframe.Helpers$ImageMetadata { *; }
-keep class com.immichframe.immichframe.Helpers$ServerSettings { *; }
-keep class com.immichframe.immichframe.Helpers$Weather { *; }

# Retrofit builds a proxy from the interface's annotations and generic return types. Retrofit's
# own rules cover this; it is kept explicitly because a stripped interface fails only at runtime.
-keep interface com.immichframe.immichframe.Helpers$ApiService { *; }
# Generic field and return types (List<String>, Call<ImageResponse>) and the HTTP annotations.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# Paho finds its network modules through ServiceLoader and its logger by class name.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
# Keep line numbers so crash stack traces from the minified frame build stay readable.
-keepattributes SourceFile,LineNumberTable
