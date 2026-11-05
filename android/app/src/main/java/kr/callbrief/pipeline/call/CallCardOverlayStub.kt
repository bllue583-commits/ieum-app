package kr.callbrief.pipeline.call

/** Placeholder so r11-1 compiles on its own: r11-2 makes the real `CallCardOverlay` (CallCardOverlay.kt); this file is removed
 * when the two are put together. Same names and signatures as the real one. */
internal object CallCardOverlay { fun show(context: android.content.Context, phoneKey:String?, state: CallSession.State) {}; fun collapse() {}; fun expand() {}; fun hide() {} }
