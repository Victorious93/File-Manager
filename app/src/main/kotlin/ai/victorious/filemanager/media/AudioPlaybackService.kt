package ai.victorious.filemanager.media

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Media Pro session service: hosts the shared [MediaProEngine] player in a MediaSession so
 * background play, lock-screen and notification controls work for audio and video.
 */
@UnstableApi
class AudioPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, MediaProEngine.createPlayer(this)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        MediaProEngine.releasePlayer()
        super.onDestroy()
    }
}
