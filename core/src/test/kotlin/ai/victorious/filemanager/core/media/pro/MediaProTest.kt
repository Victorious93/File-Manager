package ai.victorious.filemanager.core.media.pro

import ai.victorious.filemanager.core.fleet.BridgeExecutor
import ai.victorious.filemanager.core.fleet.BridgeRequest
import ai.victorious.filemanager.core.fleet.BridgeResponse
import ai.victorious.filemanager.core.fleet.CallerIdentity
import ai.victorious.filemanager.core.fleet.CallerPolicy
import ai.victorious.filemanager.core.fleet.RemoteError
import ai.victorious.filemanager.core.fleet.bridgeJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FakeRemote : MediaRemote {
    var loaded = true
    var playing = false
    var pos = 10_000L
    val dur = 100_000L
    var volume = 100
    var index = 0
    val calls = mutableListOf<String>()
    override fun state() = if (!loaded) null else MediaState(playing, pos, dur, "Song $index", "local")
    private fun op(n: String, f: () -> Unit): Boolean { calls += n; if (!loaded) return false; f(); return true }
    override fun play() = op("play") { playing = true }
    override fun pause() = op("pause") { playing = false }
    override fun toggle() = op("toggle") { playing = !playing }
    override fun next() = op("next") { index++ }
    override fun prev() = op("prev") { index-- }
    override fun seekTo(positionMs: Long) = op("seek") { pos = positionMs.coerceAtMost(dur) }
    override fun seekBy(deltaMs: Long) = op("seekBy") { pos = (pos + deltaMs).coerceIn(0, dur) }
    override fun setVolume(level: Int) = op("volume") { volume = level }
}

class MediaBridgeTest {
    private val node = "fm-0123456789abcdef0123456789abcdef"
    private var now = 1_760_920_000_000L
    private var n = 0
    private var enabled = true
    private val remote = FakeRemote()
    private val ex = BridgeExecutor(node, externalControlEnabled = { enabled }, clock = { now }, media = remote)
    private val kb = CallerIdentity(10200, listOf(CallerPolicy.KEYBOARD_DEBUG),
        mapOf(CallerPolicy.KEYBOARD_DEBUG to listOf(CallerPolicy.normalizeDigest(CallerPolicy.KEYBOARD_DEBUG_CERT_SHA256))))

    private fun cmd(action: String, args: Map<String, JsonElement> = emptyMap(), caller: CallerIdentity = kb): BridgeResponse {
        val p = mapOf("nonce" to JsonPrimitive("%032x".format(++n)), "timestampMs" to JsonPrimitive(now),
            "actionRef" to JsonPrimitive(action), "args" to JsonObject(args))
        val j = bridgeJson.encodeToString(BridgeRequest.serializer(), BridgeRequest(requestId = "r$n", operation = "remote.command", parameters = JsonObject(p)))
        return bridgeJson.decodeFromString(BridgeResponse.serializer(), ex.handle(j, caller))
    }

    @Test fun keyboardCanControlPlayback() {
        assertTrue(cmd("media.play").ok); assertTrue(remote.playing)
        assertTrue(cmd("media.pause").ok); assertFalse(remote.playing)
        assertTrue(cmd("media.toggle").ok); assertTrue(remote.playing)
        assertTrue(cmd("media.next").ok); assertEquals(1, remote.index)
        assertTrue(cmd("media.prev").ok); assertEquals(0, remote.index)
    }

    @Test fun stateShape() {
        val r = cmd("media.state")
        assertTrue(r.ok)
        assertEquals(setOf("playing", "positionMs", "durationMs", "title", "source"), r.result!!.keys)
        assertEquals("10000", r.result!!["positionMs"]!!.jsonPrimitive.content)
        assertEquals("local", r.result!!["source"]!!.jsonPrimitive.content)
    }

    @Test fun seekAndSeekBy() {
        assertEquals("42000", cmd("media.seek", mapOf("positionMs" to JsonPrimitive(42_000))).result!!["positionMs"]!!.jsonPrimitive.content)
        assertEquals("37000", cmd("media.seekBy", mapOf("deltaMs" to JsonPrimitive(-5_000))).result!!["positionMs"]!!.jsonPrimitive.content)
        assertEquals(RemoteError.INVALID_PARAMETERS, cmd("media.seek", mapOf("positionMs" to JsonPrimitive(-1))).error!!.category)
        assertEquals(RemoteError.INVALID_PARAMETERS, cmd("media.seek").error!!.category)
        assertEquals(RemoteError.INVALID_PARAMETERS, cmd("media.seekBy", mapOf("deltaMs" to JsonPrimitive("abc"))).error!!.category)
    }

    @Test fun volumeRange() {
        assertTrue(cmd("media.volume", mapOf("level" to JsonPrimitive(30))).ok)
        assertEquals(30, remote.volume)
        assertTrue(cmd("media.volume", mapOf("level" to JsonPrimitive(0))).ok)
        assertTrue(cmd("media.volume", mapOf("level" to JsonPrimitive(100))).ok)
        assertEquals(RemoteError.INVALID_PARAMETERS, cmd("media.volume", mapOf("level" to JsonPrimitive(101))).error!!.category)
        assertEquals(RemoteError.INVALID_PARAMETERS, cmd("media.volume", mapOf("level" to JsonPrimitive(-1))).error!!.category)
        assertEquals(100, remote.volume)
    }

    @Test fun idleIsNoSession() {
        remote.loaded = false
        for (op in MediaBridgeOps.NAMES) {
            val args = mapOf("positionMs" to JsonPrimitive(1), "deltaMs" to JsonPrimitive(1), "level" to JsonPrimitive(1))
            assertEquals(op, MediaBridgeOps.NO_SESSION, cmd(op, args).error!!.category)
        }
    }

    @Test fun externalControlOffBlocksKeyboard() {
        enabled = false
        assertEquals(RemoteError.EXTERNAL_CONTROL_DISABLED, cmd("media.play").error!!.category)
        assertTrue(remote.calls.isEmpty())
    }

    @Test fun keyboardWrongCertOrReleaseDenied() {
        val fake = CallerIdentity(1, listOf(CallerPolicy.KEYBOARD_DEBUG), mapOf(CallerPolicy.KEYBOARD_DEBUG to listOf("ee".repeat(32))))
        assertEquals(RemoteError.UNAUTHORIZED, cmd("media.play", caller = fake).error!!.category)
        val rel = CallerIdentity(2, listOf(CallerPolicy.KEYBOARD_RELEASE), emptyMap())
        assertEquals(RemoteError.NOT_PAIRED, cmd("media.play", caller = rel).error!!.category)
        assertTrue(remote.calls.isEmpty())
    }

    @Test fun keyboardStillMediaOnly() {
        assertEquals(RemoteError.UNAUTHORIZED, cmd("node.status.get").error!!.category)
    }

    @Test fun descriptorsAdvertisedForDca() {
        val dca = CallerIdentity(1, listOf("ai.droidcommand.app"))
        val p = mapOf("nonce" to JsonPrimitive("%032x".format(++n)), "timestampMs" to JsonPrimitive(now))
        val j = bridgeJson.encodeToString(BridgeRequest.serializer(), BridgeRequest(requestId = "c", operation = "capabilities.get", parameters = JsonObject(p)))
        val ops = bridgeJson.decodeFromString(BridgeResponse.serializer(), ex.handle(j, dca)).result!!["ops"]!!.jsonArray
            .map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        for (name in MediaBridgeOps.NAMES) {
            val d = ops.getValue(name)
            assertEquals(if (name == "media.state") "read" else "action", d["kind"]!!.jsonPrimitive.content)
            assertEquals("true", d["remoteAllowed"]!!.jsonPrimitive.content)
            assertEquals("false", d["requiresConfirm"]!!.jsonPrimitive.content)
        }
    }

    @Test fun noPlayerIsCapabilityOff() {
        val plain = BridgeExecutor(node, externalControlEnabled = { true }, clock = { now })
        val p = mapOf("nonce" to JsonPrimitive("%032x".format(++n)), "timestampMs" to JsonPrimitive(now), "actionRef" to JsonPrimitive("media.play"))
        val j = bridgeJson.encodeToString(BridgeRequest.serializer(), BridgeRequest(requestId = "x", operation = "remote.command", parameters = JsonObject(p)))
        assertEquals(RemoteError.CAPABILITY_OFF, bridgeJson.decodeFromString(BridgeResponse.serializer(), plain.handle(j, kb)).error!!.category)
    }
}

class PlayQueueTest {
    private fun items(k: Int) = List(k) { MediaItemRef("id$it", "/m/$it.mp3", "T$it") }

    @Test fun nextPrevAndEnd() {
        val q = PlayQueue(); q.setAll(items(3))
        assertEquals("id0", q.current()!!.id)
        assertEquals("id1", q.next()!!.id); assertEquals("id2", q.next()!!.id)
        assertNull(q.next())
        assertEquals("id1", q.prev()!!.id)
    }

    @Test fun repeatAllWraps() {
        val q = PlayQueue(); q.setAll(items(2)); q.repeat = RepeatMode.ALL
        q.next(); assertEquals("id0", q.next()!!.id)
        assertEquals("id1", q.prev()!!.id)
    }

    @Test fun repeatOneAutoAdvanceStays() {
        val q = PlayQueue(); q.setAll(items(2)); q.repeat = RepeatMode.ONE
        assertEquals("id0", q.next(userInitiated = false)!!.id)
        assertEquals("id1", q.next(userInitiated = true)!!.id)
    }

    @Test fun shuffleKeepsCurrentAndIsPermutation() {
        val q = PlayQueue(Random(7)); q.setAll(items(10), startIndex = 4)
        q.setShuffle(true)
        assertEquals("id4", q.current()!!.id)
        assertEquals(items(10).map { it.id }.toSet(), q.playOrder().map { it.id }.toSet())
        assertEquals(10, q.playOrder().size)
        q.next(); val cur = q.current()!!.id
        q.setShuffle(false)
        assertEquals(cur, q.current()!!.id)
        assertEquals(items(10).map { it.id }, q.playOrder().map { it.id })
    }

    @Test fun playNextAddRemoveMove() {
        val q = PlayQueue(); q.setAll(items(3))
        q.playNext(MediaItemRef("x", "/x", "X"))
        assertEquals(listOf("id0", "x", "id1", "id2"), q.playOrder().map { it.id })
        q.next(); q.remove("id0")
        assertEquals("x", q.current()!!.id)
        q.move(2, 0)
        assertEquals(listOf("id2", "x", "id1"), q.playOrder().map { it.id })
        assertEquals("x", q.current()!!.id)
        q.add(MediaItemRef("y", "/y", "Y")); assertEquals(4, q.size)
        assertEquals("id1", q.jumpTo("id1")!!.id)
    }

    @Test fun emptyQueue() {
        val q = PlayQueue()
        assertNull(q.current()); assertNull(q.next()); assertNull(q.prev())
    }

    @Test fun playlistsRoundTrip() {
        val lists = listOf(Playlist("p1", "Gym", items(2)), Playlist("p2", "Empty", emptyList()))
        assertEquals(lists, PlaylistCodec.decode(PlaylistCodec.encode(lists)))
        assertEquals(emptyList<Playlist>(), PlaylistCodec.decode("garbage"))
    }
}

class MediaProUtilTest {
    @Test fun resumeRules() {
        val r = ResumeStore(maxEntries = 2)
        r.save("a", 3_000, 100_000); assertNull(r.resumeAt("a"))
        r.save("a", 50_000, 100_000); assertEquals(50_000L, r.resumeAt("a"))
        r.save("a", 96_000, 100_000); assertNull(r.resumeAt("a"))
        r.save("b", 10_000, 0); r.save("c", 10_000, 0); r.save("d", 10_000, 0)
        assertEquals(setOf("c", "d"), r.snapshot().keys)
    }

    @Test fun sleepTimer() {
        var t = 0L
        val s = SleepTimer { t }
        s.start(60_000)
        assertFalse(s.shouldStop()); assertEquals(60_000L, s.remainingMs())
        t = 60_000; assertTrue(s.shouldStop()); assertFalse(s.active)
        s.startEndOfTrack(); assertFalse(s.shouldStop(trackEnded = false)); assertTrue(s.shouldStop(trackEnded = true))
        s.start(1); s.cancel(); t = 1_000_000; assertFalse(s.shouldStop())
    }

    @Test fun streamKinds() {
        assertEquals(StreamKind.HTTP, StreamSupport.kind("https://x/y.m3u8"))
        assertEquals(StreamKind.RTSP, StreamSupport.kind("rtsp://cam/live"))
        assertEquals(StreamKind.SMB, StreamSupport.kind("smb://nas/share/a.mkv"))
        assertEquals(StreamKind.LOCAL, StreamSupport.kind("/sdcard/a.mp4"))
        assertEquals(StreamKind.CONTENT, StreamSupport.kind("content://media/1"))
        assertNull(StreamSupport.kind("ftp://x"))
        assertEquals("smb", StreamSupport.label("smb://u:p@nas/s/a"))
    }

    @Test fun smbParse() {
        val t = StreamSupport.parseSmb("smb://WORK;bob:p%40ss@nas.local/media/Movies/My%20Film.mkv")!!
        assertEquals("nas.local", t.host); assertEquals("media", t.share); assertEquals("Movies\\My Film.mkv", t.path)
        assertEquals("bob", t.user); assertEquals("p@ss", t.password); assertEquals("WORK", t.domain)
        val anon = StreamSupport.parseSmb("smb://nas/share/a.mp3")!!
        assertNull(anon.user)
        assertNull(StreamSupport.parseSmb("smb://nas/share"))
        assertEquals("smb://nas/s/a", StreamSupport.redact("smb://bob:secret@nas/s/a"))
        assertEquals("http://h/x", StreamSupport.redact("http://u:p@h/x"))
    }

    @Test fun subtitles() {
        assertEquals("application/x-subrip", SubtitleSupport.mimeFor("a.SRT"))
        assertEquals("text/x-ssa", SubtitleSupport.mimeFor("a.ass"))
        assertNull(SubtitleSupport.mimeFor("a.txt"))
        val sibs = listOf("Movie.mkv", "Movie.srt", "Movie.en.ass", "Movie2.srt", "notes.txt", "movie.fr.vtt")
        assertEquals(listOf("Movie.en.ass", "Movie.srt", "movie.fr.vtt"), SubtitleSupport.sidecars("Movie.mkv", sibs))
        assertEquals("en", SubtitleSupport.language("Movie.en.ass"))
        assertNull(SubtitleSupport.language("Movie.srt"))
    }

    @Test fun gestures() {
        assertEquals(55_000L, GestureMath.seekTarget(10_000, 100_000, 0.5f))
        assertEquals(0L, GestureMath.seekTarget(10_000, 100_000, -1f))
        assertEquals(100_000L, GestureMath.seekTarget(90_000, 100_000, 1f))
        assertEquals(1f, GestureMath.level(0.8f, -0.5f))
        assertEquals(0.3f, GestureMath.level(0.5f, 0.2f), 1e-4f)
        assertTrue(GestureMath.isLeftHalf(10f, 100f))
    }

    @Test fun speedAndEq() {
        assertEquals(4f, PlaybackSpeed.clamp(9f)); assertEquals(0.25f, PlaybackSpeed.clamp(0f))
        val eq = EqualizerSettings(true, listOf(2000, -2000)).clampedTo(-1500, 1500, 3)
        assertEquals(listOf(1500, -1500, 0), eq.bandLevels)
    }
}
