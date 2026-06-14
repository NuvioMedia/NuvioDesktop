# MediaMP

MediaMP is a media player for Compose Multiplatform. It is a
wrapper over popular media player libraries like ExoPlayer on each platform.

The goal is to provide a **unified** media player abstraction for
`commonMain`, as
well as supporting backend-specific features and direct access with the underlying media player
library for advanced use cases.

Supported targets and backends:

|    Platform    | Architecture(s) | Implementation |
|:--------------:|-----------------|----------------|
|    Android     | Any             | ExoPlayer      |
| JVM on Windows | x86_64          | VLC            |
|  JVM on macOS  | x86_64, AArch64 | VLC            |
|  JVM on Linux  | x86_64          | VLC            |
|      iOS       | AArch64         | AVKit          |

Platforms that are not listed above are not supported yet. Feel free to file an issue if you need
them.

A unified MPV backend is in active development, and will be available soon.

> [!WARNING]
>
> **This is a work in progress.**
>
> No API/ABI guarantees are provided before `v0.1.0` release, but we would still like to hear your
> feedback. Please open an issue if you have any suggestions or find any bugs.

## Installation

The latest version
is: [![Maven Central](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-api)](https://img.shields.io/maven-central/v/org.openani.mediamp/mediamp-api)

### Version Catalogs

```toml
[versions]
# Replace with the latest version
mediamp = "0.0.23"

[libraries]
mediamp-all = { module = "org.openani.mediamp:mediamp-all", version.ref = "mediamp" }
```

```kotlin
dependencies {
    commonMainApi(libs.mediamp.all)
}
```

The `-all` bundle includes:

- Mediamp common APIs and Compose UI APIs
- ExoPlayer backend for Android
    - With `media3-exoplayer-hls` for streaming `.m3u8`
- AVKit backend for iOS


> [!WARNING]
> **Compatibility Warning**
>
> `-all` bundle exposes transitive dependencies on recommend backends.
> If, in the future, we develop a new backend and believe it is a better choice, the `-all` may be
> updated to the new backend. This should generally be fine unless your app accesses
> low-level APIs. Be mindful of this when updating `-all` bundles to newer versions.

### One-liner

```kotlin
dependencies {
    // Replace with the latest version
    commonMainApi("org.openani.mediamp:mediamp-all:0.0.23")
}
```

> [!TIP]
> For multi-module projects, consider detailed
> installation: [Detailed Installation](docs/detailed-installation.md).

## Usage

### Streaming Video

```kotlin
fun main() = singleWindowApplication {
    val player = rememberMediampPlayer()
    val scope = rememberCoroutineScope()
    Column {
        Button(onClick = {
            scope.launch {
                player.playUri("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4")
            }
        }) {
            Text("Play")
        }

        MediampPlayerSurface(player, Modifier.fillMaxSize())
    }
}
```

### Accessing Player Features in commonMain

#### Adjust Playback Speed

```kotlin
val player = rememberMediampPlayer()
LaunchedEffect(player) {
    player.playUri("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4")
}
Column {
    Button(onClick = {
        player.features[PlaybackSpeed]?.set(2.0f) // `null` means the platform does not support this feature
    }) {
        Text("Speed up to 2x")
    }

    MediampPlayerSurface(player, Modifier.fillMaxSize())
}
```

### Unit Testing

> [!NOTE]
> The unit testing API is **experimental** and will be changed in the future. Use at your own risk.

Add dependency:

```toml
[libraries]
mediamp-test = { module = "org.openani.mediamp:mediamp-test", version.ref = "mediamp" }
```

```kotlin
dependencies {
    commonTestApi(libs.mediamp.test)
}
```

A mock player `TestMediampPlayer` is provided for unit testing.
It implements all the features and follow the same specification (e.g. state transitions) as the
real
player.

```kotlin
import kotlinx.coroutines.test.runTest

class MyTest {
    private val player = TestMediampPlayer()

    fun test() = runTest {
        player.playUri("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4") // Will not actually make network requests
        player.currentPositionMillis.value = 1000L // Move playback position to 1s

        assertEquals(PlaybackState.PLAYING, player.getCurrentPlaybackState())
    }
}
```

## Advanced Usages

### Custom Media Data

```kotlin
fun main() = singleWindowApplication {
    val player = rememberMediampPlayer()
    val scope = rememberCoroutineScope()

    Column {
        Button(onClick = {
            scope.launch {
                player.setMediaData(createMediaData())
                player.resume()
            }
        }) {
            Text("Play")
        }

        MediampPlayerSurface(player, Modifier.fillMaxSize())
    }
}

fun createMediaData(): SeekableInputMediaData {
    // Implement SeekableInputMediaData. 
    // It's like implementing a kotlinx-io Input with random-access seeking.
}
```

If you use kotlinx-io, you might consider the `BufferedSeekableInput` provided by
`mediamp-source-ktxio` in helping the
custom implementation of I/O operations:

```toml
[libraries]
mediamp-source-ktxio = { module = "org.openani.mediamp:mediamp-source-ktxio", version.ref = "mediamp" }
```

```kotlin
dependencies {
    commonMainApi(libs.mediamp.source.ktxio)
}
```

### Obtaining the Platform Player

Access the underlying Android `ExoPlayer`, [vlcj][vlcj] `EmbeddedMediaPlayer` and iOS `AVPlayer` for
advanced use cases.

```kotlin
// On Android
val player = ExoPlayerMediampPlayer()
val platform: ExoPlayer = player.impl
```

```kotlin
// On iOS
val player = AVKitMediampPlayer()
val platform: AVPlayer = player.impl
```

```kotlin
// On Desktop
val player = VlcMediampPlayer()
val platform: EmbeddedMediaPlayer = player.impl
```

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val player: MediampPlayer = rememberMediampPlayer()
            Column {
                Button(onClick = {
                    Toast.makeText(
                        this@MainActivity,
                        "The backend is ${player.impl as ExoPlayer}!",
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text("Play")
                }

                MediampPlayerSurface(player, Modifier.fillMaxSize())
            }
        }
    }
}
```

## License

MediaMP is mainly licensed under the Apache License version 2. However, depending on the license of
transitive dependencies, the backend-specific implementations may have different licenses.

A breakdown of the licenses:

- mediamp-exoplayer: Apache License 2.0 (Apache-v2)
- mediamp-mpv: Apache License 2.0
- All other modules: Apache License 2.0

You can find the full license text of Apache-v2 in the `LICENSE` file from the root of the
repository.

