package com.googlyeyes.companion

import android.app.Application

/** Holds the app's long-lived parts, shared by every screen. */
class CompanionApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(app: Application) {
    val prefs = PrefsRepository(app)
    val link = MacLink(app, prefs)
    val store = SessionStore(app)
    val focus = FocusClient(app)
    val voice = LiveVoice(app, prefs)
    val animator = FaceAnimator()
    val actions = Actions(this)

    init {
        focus.attach(link)
        Wiring.connect(this)
    }
}
