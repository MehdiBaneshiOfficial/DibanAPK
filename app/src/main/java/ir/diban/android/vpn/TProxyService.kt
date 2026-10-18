package ir.diban.android.vpn

/**
 * JNI surface of hev-socks5-tunnel (built from src/main/jni).  The native side registers
 * these methods on this exact class: PKGNAME=ir/diban/android/vpn, CLSNAME=TProxyService
 * (see cFlags in app/build.gradle.kts).  Do not rename or move this object.
 */
@Suppress("FunctionName")
object TProxyService {
    init { System.loadLibrary("hev-socks5-tunnel") }

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    @JvmStatic external fun TProxyGetStats(): LongArray
}
