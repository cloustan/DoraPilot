package com.dorapilot.assistant

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * In-process device controls that do NOT require launching a settings Activity:
 * flashlight (torch), media transport, and volume. These run synchronously and
 * return a normalized {ok, spoken, ...} JSON result so the assistant can confirm.
 */
class DeviceControlServer(private val context: Context) {

    private val cameraManager: CameraManager? =
        runCatching { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }.getOrNull()
    private val audioManager: AudioManager? =
        runCatching { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()

    @Volatile
    private var torchOn: Boolean = false

    private val torchCameraId: String? by lazy { findTorchCameraId() }

    private fun findTorchCameraId(): String? {
        val manager = cameraManager ?: return null
        return runCatching {
            manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    /** desired: "on", "off", or "toggle". */
    fun setTorch(desired: String): JSONObject {
        val manager = cameraManager
            ?: return fail("Camera service unavailable on this device.")
        val cameraId = torchCameraId
            ?: return fail("This device has no controllable flashlight.")

        val target = when (desired.trim().lowercase()) {
            "on", "true", "enable", "enabled" -> true
            "off", "false", "disable", "disabled" -> false
            "toggle" -> !torchOn
            else -> return fail("Unknown torch state: $desired")
        }

        return runCatching {
            manager.setTorchMode(cameraId, target)
            torchOn = target
            ok(if (target) "Flashlight on." else "Flashlight off.")
                .put("torch_on", target)
        }.getOrElse { error ->
            fail(error.message ?: "Failed to control flashlight.")
        }
    }

    /** command: play, pause, play_pause, next, previous, stop. */
    fun mediaControl(command: String): JSONObject {
        val manager = audioManager ?: return fail("Audio service unavailable.")
        val normalized = command.trim().lowercase()
        val (keyCode, spoken) = when (normalized) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY to "Playing."
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Paused."
            "play_pause", "toggle", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Toggled playback."
            "next", "skip", "forward" -> KeyEvent.KEYCODE_MEDIA_NEXT to "Skipped to next track."
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Back to previous track."
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP to "Stopped playback."
            else -> return fail("Unknown media command: $command")
        }
        return runCatching {
            val now = SystemClock.uptimeMillis()
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            ok(spoken).put("command", normalized)
        }.getOrElse { error ->
            fail(error.message ?: "Failed to send media command.")
        }
    }

    /**
     * Adjust media volume. Provide either `percent` (0-100) for an absolute level,
     * or `direction` of up/down/mute/unmute/max for a relative change.
     */
    fun setVolume(percent: Int?, direction: String?): JSONObject {
        val manager = audioManager ?: return fail("Audio service unavailable.")
        val stream = AudioManager.STREAM_MUSIC
        val max = manager.getStreamMaxVolume(stream).coerceAtLeast(1)
        val flags = AudioManager.FLAG_SHOW_UI

        return runCatching {
            when {
                percent != null -> {
                    val clamped = percent.coerceIn(0, 100)
                    val index = (clamped / 100.0 * max).roundToInt().coerceIn(0, max)
                    manager.setStreamVolume(stream, index, flags)
                    ok("Volume set to $clamped%.").put("percent", clamped)
                }
                else -> when (direction?.trim()?.lowercase()) {
                    "up", "raise", "increase", "louder" -> {
                        manager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
                        ok("Volume up.")
                    }
                    "down", "lower", "decrease", "quieter" -> {
                        manager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
                        ok("Volume down.")
                    }
                    "mute" -> {
                        manager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
                        ok("Muted.")
                    }
                    "unmute" -> {
                        manager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
                        ok("Unmuted.")
                    }
                    "max", "full" -> {
                        manager.setStreamVolume(stream, max, flags)
                        ok("Volume at maximum.").put("percent", 100)
                    }
                    else -> return fail("Provide percent or a volume direction.")
                }
            }.also {
                val current = manager.getStreamVolume(stream)
                it.put("level", current).put("max", max)
            }
        }.getOrElse { error ->
            fail(error.message ?: "Failed to adjust volume.")
        }
    }

    private fun ok(spoken: String): JSONObject =
        JSONObject().put("ok", true).put("spoken", spoken)

    private fun fail(error: String): JSONObject =
        JSONObject().put("ok", false).put("error", error)
}
