package ai.victorious.filemanager.media

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background audio playback via Media3. The platform [MediaSessionService] + Media3's default media
 * notification provider give us lock-screen / notification transport controls and background
 * playback for free; the in-app [ai.victorious.filemanager.ui.AudioPlayerScreen] drives this through
 * a MediaController. Written from scratch; no MT/NP code involved.
 */
class AudioPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplugged
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // If nothing is playing when the task is swiped away, stop the service so no empty
        // notification lingers.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
