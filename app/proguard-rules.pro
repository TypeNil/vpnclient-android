# --- libbox JNI callbacks -------------------------------------------------
# gomobile invokes these objects' methods by name from native code. The AAR's
# consumer rules keep the io.nekohasekai.libbox interfaces themselves; these
# rules keep our implementations (and their method names) intact under R8.
-keep class dev.typenil.vpnclient.core.engine.singbox.SingBoxEngine$PlatformBridge {
    *;
}
-keep class dev.typenil.vpnclient.core.engine.singbox.SingBoxEngine$ServerHandler {
    *;
}
-keep class dev.typenil.vpnclient.core.engine.singbox.SingBoxEngine$ClientHandler {
    *;
}
-keep class dev.typenil.vpnclient.core.engine.singbox.LocalDnsResolver {
    *;
}
-keep class dev.typenil.vpnclient.core.engine.singbox.LocalDnsResolver$CancelFunc {
    *;
}
-keep class dev.typenil.vpnclient.core.engine.singbox.NetworkMonitor$InterfaceArray {
    *;
}
-keep class dev.typenil.vpnclient.core.engine.singbox.NetworkMonitor$StringArray {
    *;
}
