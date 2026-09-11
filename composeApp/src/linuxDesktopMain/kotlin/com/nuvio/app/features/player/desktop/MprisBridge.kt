package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerNowPlayingInfo
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusIgnore
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.DBusProperties
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal object MprisBridge {
    private const val BUS_NAME = "org.mpris.MediaPlayer2.nuvio"
    private const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
    private const val ROOT_INTERFACE = "org.mpris.MediaPlayer2"
    private const val PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
    private const val TRACK_PATH_PREFIX = "/com/nuvio/app/mpris/track/"
    private const val MAX_VOLUME = 2.0

    private val log = Logger.withTag("MprisBridge")
    private val lock = Any()
    private var startAttempted = false
    private var connection: DBusConnection? = null
    private var exportedObject: MprisObject? = null
    private var metadata: PlayerNowPlayingInfo? = null
    private var playback = PlayerPlaybackSnapshot()
    private var activeController: NativePlayerController? = null
    private val commandTarget = AtomicReference<((MprisCommand) -> Unit)?>(null)

    fun start() {
        if (DesktopHostOs.current != DesktopHostOs.LINUX) return
        synchronized(lock) {
            if (startAttempted) return
            startAttempted = true
            var candidate: DBusConnection? = null
            runCatching {
                val bus = DBusConnectionBuilder.forSessionBus().build().also { candidate = it }
                bus.requestBusName(BUS_NAME)
                val exported = MprisObject(::dispatchCommand)
                bus.exportObject(OBJECT_PATH, exported)
                connection = bus
                exportedObject = exported
                log.i { "MPRIS service registered as $BUS_NAME" }
            }.onFailure { error ->
                runCatching { candidate?.close() }
                log.w(error) { "MPRIS unavailable; continuing without desktop media controls" }
                connection = null
                exportedObject = null
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            commandTarget.set(null)
            startAttempted = false
            activeController = null
            metadata = null
            playback = PlayerPlaybackSnapshot()
            val bus = connection
            connection = null
            exportedObject = null
            runCatching { bus?.unExportObject(OBJECT_PATH) }
            runCatching { bus?.releaseBusName(BUS_NAME) }
            runCatching { bus?.close() }
        }
    }

    fun register(controller: NativePlayerController) {
        start()
        synchronized(lock) {
            if (activeController !== controller) {
                metadata = null
                playback = PlayerPlaybackSnapshot()
                exportedObject?.clearState()
            }
            activeController = controller
            exportedObject?.setControllerAvailable(true)
            commandTarget.set { command -> controller.handleMprisCommand(command) }
        }
    }

    fun unregister(controller: NativePlayerController) {
        synchronized(lock) {
            if (activeController === controller) {
                activeController = null
                commandTarget.set(null)
                exportedObject?.setControllerAvailable(false)
                metadata = null
                playback = PlayerPlaybackSnapshot()
                exportedObject?.clearState()
            }
        }
    }

    fun updateMetadata(info: PlayerNowPlayingInfo) {
        start()
        synchronized(lock) {
            metadata = info
            exportedObject?.update(info, playback)
        }
    }

    fun updatePlayback(snapshot: PlayerPlaybackSnapshot) {
        start()
        synchronized(lock) {
            playback = snapshot
            exportedObject?.update(metadata, snapshot)
        }
    }

    fun updateVolume(level: Float) {
        synchronized(lock) { exportedObject?.updateVolumeLevel(level.toDouble()) }
    }

    fun updateNavigation(canGoNext: Boolean, canGoPrevious: Boolean) {
        synchronized(lock) { exportedObject?.setNavigationCapabilities(canGoNext, canGoPrevious) }
    }

    fun notifySeek(positionMs: Long) {
        synchronized(lock) { exportedObject?.seeked(max(0L, positionMs) * 1_000L) }
    }

    fun clear() {
        synchronized(lock) {
            metadata = null
            playback = PlayerPlaybackSnapshot()
            exportedObject?.clearState()
        }
    }

    private fun dispatchCommand(command: MprisCommand) {
        runCatching { commandTarget.get()?.invoke(command) }
            .onFailure { error -> log.w(error) { "MPRIS command failed: $command" } }
    }

    @DBusInterfaceName(ROOT_INTERFACE)
    @DBusProperties(
        value = [
            DBusProperty(name = "CanQuit", type = Boolean::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "CanRaise", type = Boolean::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "HasTrackList", type = Boolean::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "Identity", type = String::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "DesktopEntry", type = String::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "SupportedUriSchemes", type = StringListType::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "SupportedMimeTypes", type = StringListType::class, access = DBusProperty.Access.READ),
        ],
    )
    interface RootInterface : DBusInterface {
        @DBusIgnore fun getCanQuit(): Boolean
        @DBusIgnore fun getCanRaise(): Boolean
        @DBusIgnore fun getHasTrackList(): Boolean
        @DBusIgnore fun getIdentity(): String
        @DBusIgnore fun getDesktopEntry(): String
        @DBusIgnore fun getSupportedUriSchemes(): List<String>
        @DBusIgnore fun getSupportedMimeTypes(): List<String>

        @DBusMemberName("Raise") fun raise()
        @DBusMemberName("Quit") fun quit()
    }

    interface StringListType : TypeRef<List<String>>
    interface MetadataType : TypeRef<MutableMap<String, Variant<Any>>>

    @DBusInterfaceName(PLAYER_INTERFACE)
    @DBusProperties(
        value = [
            DBusProperty(name = "PlaybackStatus", type = String::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "LoopStatus", type = String::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "Rate", type = Double::class, access = DBusProperty.Access.READ_WRITE),
            DBusProperty(name = "Shuffle", type = Boolean::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "Metadata", type = MetadataType::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "Volume", type = Double::class, access = DBusProperty.Access.READ_WRITE),
            DBusProperty(name = "Position", type = Long::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "MinimumRate", type = Double::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "MaximumRate", type = Double::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "CanGoNext", type = Boolean::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "CanPlay", type = Boolean::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "CanPause", type = Boolean::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "CanSeek", type = Boolean::class, access = DBusProperty.Access.READ),
            DBusProperty(name = "CanControl", type = Boolean::class, access = DBusProperty.Access.READ),
        ],
    )
    interface PlayerInterface : DBusInterface {
        @DBusIgnore fun getPlaybackStatus(): String
        @DBusIgnore fun getLoopStatus(): String
        @DBusIgnore fun getRate(): Double
        @DBusIgnore fun setRate(value: Double)
        @DBusIgnore fun getShuffle(): Boolean
        @DBusIgnore fun getMetadata(): Map<String, Variant<Any>>
        @DBusIgnore fun getVolume(): Double
        @DBusIgnore fun setVolume(value: Double)
        @DBusIgnore fun getPosition(): Long
        @DBusIgnore fun getMinimumRate(): Double
        @DBusIgnore fun getMaximumRate(): Double
        @DBusIgnore fun getCanGoNext(): Boolean
        @DBusIgnore fun getCanGoPrevious(): Boolean
        @DBusIgnore fun getCanPlay(): Boolean
        @DBusIgnore fun getCanPause(): Boolean
        @DBusIgnore fun getCanSeek(): Boolean
        @DBusIgnore fun getCanControl(): Boolean

        @DBusMemberName("Next") fun next()
        @DBusMemberName("Previous") fun previous()
        @DBusMemberName("Pause") fun pause()
        @DBusMemberName("Play") fun play()
        @DBusMemberName("PlayPause") fun playPause()
        @DBusMemberName("Stop") fun stop()
        @DBusMemberName("Seek") fun seek(offsetUs: Long)
        @DBusMemberName("SetPosition") fun setPosition(trackId: DBusPath, positionUs: Long)

        @DBusInterfaceName(PLAYER_INTERFACE)
        class Seeked(objectPath: String, positionUs: Long) : DBusSignal(objectPath, positionUs)
    }

    internal class MprisObject(
        private val onCommand: (MprisCommand) -> Unit,
    ) : RootInterface, PlayerInterface, Properties {
        private var info: PlayerNowPlayingInfo? = null
        private var snapshot = PlayerPlaybackSnapshot()
        private var controllerAvailable = false
        private var nextAvailable = false
        private var previousAvailable = false
        private var trackSequence = 0L
        private var trackId = ""
        private var rate = 1.0
        private var volume = 1.0

        override fun getObjectPath(): String = OBJECT_PATH
        override fun getCanQuit(): Boolean = false
        override fun getCanRaise(): Boolean = false
        override fun getHasTrackList(): Boolean = false
        override fun getIdentity(): String = "Nuvio"
        override fun getDesktopEntry(): String = "nuvio"
        override fun getSupportedUriSchemes(): List<String> = emptyList()
        override fun getSupportedMimeTypes(): List<String> = emptyList()
        override fun raise() = Unit
        override fun quit() = Unit

        override fun getPlaybackStatus(): String = when {
            snapshot.isLoading || snapshot.isEnded -> "Stopped"
            snapshot.isPlaying -> "Playing"
            else -> "Paused"
        }

        override fun getLoopStatus(): String = "None"
        override fun getRate(): Double = rate
        override fun setRate(value: Double) {
            if (value.isFinite() && value in 0.25..4.0) {
                if (rate != value) {
                    rate = value
                    emitChanged(PLAYER_INTERFACE, mapOf("Rate" to Variant(value)))
                }
                onCommand(MprisCommand.SetRate(value))
            }
        }

        override fun getShuffle(): Boolean = false
        override fun getMetadata(): Map<String, Variant<Any>> = metadataFor(info, snapshot, trackId)
        override fun getVolume(): Double = volume
        override fun setVolume(value: Double) {
            if (value.isFinite()) onCommand(MprisCommand.SetVolume(value.coerceIn(0.0, MAX_VOLUME)))
        }

        override fun getPosition(): Long = max(0L, snapshot.positionMs * 1_000L)
        override fun getMinimumRate(): Double = 0.25
        override fun getMaximumRate(): Double = 4.0
        override fun getCanGoNext(): Boolean = controllerAvailable && info != null && nextAvailable
        override fun getCanGoPrevious(): Boolean = controllerAvailable && info != null && previousAvailable
        override fun getCanPlay(): Boolean = controllerAvailable
        override fun getCanPause(): Boolean = controllerAvailable
        override fun getCanSeek(): Boolean = controllerAvailable && snapshot.durationMs > 0L
        override fun getCanControl(): Boolean = true

        override fun next() {
            if (getCanGoNext()) onCommand(MprisCommand.Next)
        }

        override fun previous() {
            if (getCanGoPrevious()) onCommand(MprisCommand.Previous)
        }

        override fun pause() {
            if (getCanPause()) onCommand(MprisCommand.Pause)
        }

        override fun play() {
            if (getCanPlay()) onCommand(MprisCommand.Play)
        }

        override fun playPause() {
            if (getCanPlay() || getCanPause()) onCommand(MprisCommand.PlayPause)
        }

        override fun stop() {
            if (controllerAvailable) onCommand(MprisCommand.Stop)
        }

        override fun seek(offsetUs: Long) {
            if (getCanSeek()) onCommand(MprisCommand.Seek(offsetUs))
        }

        override fun setPosition(trackId: DBusPath, positionUs: Long) {
            val durationUs = snapshot.durationMs * 1_000L
            if (trackId.path == this.trackId && positionUs >= 0L && positionUs < durationUs && getCanSeek()) {
                onCommand(MprisCommand.SetPosition(positionUs))
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A {
            return (GetAll(interfaceName)[propertyName]
                ?: throw IllegalArgumentException("Unknown MPRIS property: $interfaceName.$propertyName")) as A
        }

        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
            val rawValue = if (value is Variant<*>) value.value else value
            when {
                interfaceName == PLAYER_INTERFACE && propertyName == "Rate" && rawValue is Number -> {
                    setRate(rawValue.toDouble())
                }
                interfaceName == PLAYER_INTERFACE && propertyName == "Volume" && rawValue is Number -> {
                    setVolume(rawValue.toDouble())
                }
                else -> throw IllegalArgumentException(
                    "MPRIS property is read-only or unknown: $interfaceName.$propertyName",
                )
            }
        }

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            ROOT_INTERFACE -> linkedMapOf(
                "CanQuit" to Variant(getCanQuit()),
                "CanRaise" to Variant(getCanRaise()),
                "HasTrackList" to Variant(getHasTrackList()),
                "Identity" to Variant(getIdentity()),
                "DesktopEntry" to Variant(getDesktopEntry()),
                "SupportedUriSchemes" to Variant(getSupportedUriSchemes(), "as"),
                "SupportedMimeTypes" to Variant(getSupportedMimeTypes(), "as"),
            )
            PLAYER_INTERFACE -> linkedMapOf(
                "PlaybackStatus" to Variant(getPlaybackStatus()),
                "LoopStatus" to Variant(getLoopStatus()),
                "Rate" to Variant(getRate()),
                "Shuffle" to Variant(getShuffle()),
                "Metadata" to Variant(getMetadata(), "a{sv}"),
                "Volume" to Variant(getVolume()),
                "Position" to Variant(getPosition()),
                "MinimumRate" to Variant(getMinimumRate()),
                "MaximumRate" to Variant(getMaximumRate()),
                "CanGoNext" to Variant(getCanGoNext()),
                "CanGoPrevious" to Variant(getCanGoPrevious()),
                "CanPlay" to Variant(getCanPlay()),
                "CanPause" to Variant(getCanPause()),
                "CanSeek" to Variant(getCanSeek()),
                "CanControl" to Variant(getCanControl()),
            )
            else -> emptyMap()
        }

        @Synchronized
        internal fun update(newInfo: PlayerNowPlayingInfo?, newSnapshot: PlayerPlaybackSnapshot): Set<String> {
            val oldStatus = getPlaybackStatus()
            val oldMetadata = getMetadata()
            val oldRate = rate
            val oldCanGoNext = getCanGoNext()
            val oldCanGoPrevious = getCanGoPrevious()
            val oldCanSeek = getCanSeek()
            if (newInfo != null && newInfo != info) {
                trackSequence += 1L
                trackId = "$TRACK_PATH_PREFIX$trackSequence"
            }
            info = newInfo
            snapshot = newSnapshot
            rate = newSnapshot.playbackSpeed.toDouble().coerceIn(0.25, 4.0)
            val changed = LinkedHashMap<String, Variant<Any>>()
            if (oldStatus != getPlaybackStatus()) changed["PlaybackStatus"] = Variant(getPlaybackStatus())
            if (oldMetadata != getMetadata()) changed["Metadata"] = Variant(getMetadata(), "a{sv}")
            if (oldRate != rate) changed["Rate"] = Variant(rate)
            if (oldCanGoNext != getCanGoNext()) changed["CanGoNext"] = Variant(getCanGoNext())
            if (oldCanGoPrevious != getCanGoPrevious()) changed["CanGoPrevious"] = Variant(getCanGoPrevious())
            if (oldCanSeek != getCanSeek()) changed["CanSeek"] = Variant(getCanSeek())
            if (changed.isNotEmpty()) emitChanged(PLAYER_INTERFACE, changed)
            return changed.keys
        }

        @Synchronized
        fun clearState() {
            val oldStatus = getPlaybackStatus()
            val oldCanGoNext = getCanGoNext()
            val oldCanGoPrevious = getCanGoPrevious()
            val oldCanSeek = getCanSeek()
            info = null
            snapshot = PlayerPlaybackSnapshot()
            trackId = ""
            val changed = LinkedHashMap<String, Variant<Any>>()
            if (oldStatus != getPlaybackStatus()) changed["PlaybackStatus"] = Variant(getPlaybackStatus())
            changed["Metadata"] = Variant(getMetadata(), "a{sv}")
            if (oldCanGoNext != getCanGoNext()) changed["CanGoNext"] = Variant(getCanGoNext())
            if (oldCanGoPrevious != getCanGoPrevious()) changed["CanGoPrevious"] = Variant(getCanGoPrevious())
            if (oldCanSeek != getCanSeek()) changed["CanSeek"] = Variant(getCanSeek())
            emitChanged(PLAYER_INTERFACE, changed)
        }

        @Synchronized
        internal fun setControllerAvailable(available: Boolean) {
            if (controllerAvailable == available) return
            val oldCanGoNext = getCanGoNext()
            val oldCanGoPrevious = getCanGoPrevious()
            val oldCanSeek = getCanSeek()
            controllerAvailable = available
            val changed = LinkedHashMap<String, Variant<Any>>()
            changed["CanPlay"] = Variant(available)
            changed["CanPause"] = Variant(available)
            if (oldCanGoNext != getCanGoNext()) changed["CanGoNext"] = Variant(getCanGoNext())
            if (oldCanGoPrevious != getCanGoPrevious()) changed["CanGoPrevious"] = Variant(getCanGoPrevious())
            if (oldCanSeek != getCanSeek()) changed["CanSeek"] = Variant(getCanSeek())
            emitChanged(
                PLAYER_INTERFACE,
                changed,
            )
        }

        @Synchronized
        internal fun setNavigationCapabilities(canGoNext: Boolean, canGoPrevious: Boolean) {
            val oldCanGoNext = getCanGoNext()
            val oldCanGoPrevious = getCanGoPrevious()
            nextAvailable = canGoNext
            previousAvailable = canGoPrevious
            val changed = LinkedHashMap<String, Variant<Any>>()
            if (oldCanGoNext != getCanGoNext()) changed["CanGoNext"] = Variant(getCanGoNext())
            if (oldCanGoPrevious != getCanGoPrevious()) changed["CanGoPrevious"] = Variant(getCanGoPrevious())
            if (changed.isNotEmpty()) emitChanged(PLAYER_INTERFACE, changed)
        }

        @Synchronized
        fun updateVolumeLevel(value: Double) {
            val clamped = value.coerceIn(0.0, MAX_VOLUME)
            if (clamped != volume) {
                volume = clamped
                emitChanged(PLAYER_INTERFACE, mapOf("Volume" to Variant(clamped)))
            }
        }

        private fun emitChanged(interfaceName: String, changed: Map<String, Variant<Any>>) {
            val bus = connection ?: return
            runCatching {
                bus.sendMessage(Properties.PropertiesChanged(OBJECT_PATH, interfaceName, changed, emptyList()))
            }.onFailure { error -> log.d(error) { "Unable to emit MPRIS PropertiesChanged" } }
        }

        fun seeked(positionUs: Long) {
            val bus = connection ?: return
            runCatching {
                bus.sendMessage(PlayerInterface.Seeked(OBJECT_PATH, positionUs))
            }.onFailure { error -> log.d(error) { "Unable to emit MPRIS Seeked" } }
        }
    }

    private fun metadataFor(
        info: PlayerNowPlayingInfo?,
        snapshot: PlayerPlaybackSnapshot,
        trackId: String,
    ): Map<String, Variant<Any>> {
        if (info == null || info.title.isBlank() || trackId.isBlank()) return LinkedHashMap()
        val values = LinkedHashMap<String, Variant<Any>>()
        values["mpris:trackid"] = Variant(trackId, "o")
        values["xesam:title"] = Variant(info.title)
        info.artworkUrl?.takeIf(String::isNotBlank)?.let { values["mpris:artUrl"] = Variant(it) }
        if (snapshot.durationMs > 0L) values["mpris:length"] = Variant(snapshot.durationMs * 1_000L)
        return values
    }
}
