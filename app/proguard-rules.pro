# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/com.amnesica/Android/Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

-keep class com.amnesica.kryptey.inputmethod.R
# ---------------------------------------------------------------------------
# Reflection keep rules.
#
# minifyEnabled is currently false, so none of this is exercised yet. It is
# written now because AGP 9 forces proguard-android-optimize.txt, which drops
# -dontoptimize and adds -allowaccessmodification: whoever turns minification on
# next would otherwise hit silent, hard-to-diagnose breakage in exactly the code
# that persists the user's identity keys.
# ---------------------------------------------------------------------------

# Jackson serializes the whole Signal protocol store by reflection over fields
# and @JsonProperty-annotated constructors. Losing any of these turns a stored
# key into null at load time, which StorageHelper swallows into an NPE.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.amnesica.kryptey.inputmethod.signalprotocol.** { *; }
-keepclassmembers class com.amnesica.kryptey.inputmethod.signalprotocol.** {
    @com.fasterxml.jackson.annotation.JsonProperty *;
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
}

# JsonUtil calls objectMapper.findAndRegisterModules(), which is a ServiceLoader
# lookup over META-INF/services. Strip the implementations and Instant silently
# changes representation instead of failing loudly.
-keep class com.fasterxml.jackson.databind.Module { *; }
-keep class * extends com.fasterxml.jackson.databind.Module { *; }
-keep class com.fasterxml.jackson.datatype.jsr310.** { *; }
-dontwarn com.fasterxml.jackson.**

# libsignal resolves JNI entry points and record types by name.
-keep class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**

# PreferenceActivity instantiates fragments by class name (SettingsActivity
# passes SettingsFragment.class.getName() and validates via FragmentUtils), and
# custom Preference subclasses are inflated by name from prefs_screen_*.xml.
-keep class * extends android.preference.Preference { *; }
-keep class * extends android.preference.PreferenceFragment { *; }
-keep class * extends android.app.Fragment { *; }

# Views inflated from XML need their (Context, AttributeSet) constructors.
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
