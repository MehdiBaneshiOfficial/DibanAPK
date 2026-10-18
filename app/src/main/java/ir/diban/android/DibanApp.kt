package ir.diban.android

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class DibanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        // Config, CA and key live here; app-private and excluded from backups (see manifest).
        Bridge.init(File(filesDir, "diban").absolutePath)
    }
}
