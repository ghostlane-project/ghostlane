-keep class org.olcbox.app.vpn.service.OlcboxVpnService {
    *;
}

# The Rust core (ghostlane-core; built only with -PghostlaneCoreDir). The service finds
# RustCoreEngine by name; the UniFFI bindings and the xray-ffi calls go through JNA, which
# looks up classes, fields and callback methods by name and native symbols by method name.
-keep class org.olcbox.app.rustcore.** { *; }
-keep class org.olcbox.app.vpn.service.RustCoreBackend { *; }
-keep class org.olcbox.app.vpn.service.RustOlcParams { *; }
-keep class org.ghostlane.olcrtc.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
