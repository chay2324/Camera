package com.rawcam

import java.util.Locale

/** What the user asked for by voice. */
sealed class Cmd {
    object Photo : Cmd()
    object StartRec : Cmd()
    object StopRec : Cmd()
    data class SetMode(val mode: Mode) : Cmd()
    data class ZoomBy(val factor: Float) : Cmd()
    data class ZoomTo(val z: Float) : Cmd()
    /** ratio = 0.5, 1, 2 ... ; -1 means "the longest lens" */
    data class LensRatio(val ratio: Float) : Cmd()
    data class Facing(val back: Boolean) : Cmd()
    data class Flash(val mode: FlashMode) : Cmd()
    data class Fmt(val format: Format) : Cmd()
    data class Natural(val on: Boolean) : Cmd()
    data class Timer(val seconds: Int) : Cmd()
    data class Grid(val on: Boolean) : Cmd()
    data class Iso(val value: Int) : Cmd()
    data class Shutter(val ns: Long) : Cmd()
    object AutoExposure : Cmd()
    data class Ev(val stops: Float, val relative: Boolean) : Cmd()
    data class ManualFocus(val on: Boolean) : Cmd()
    data class FocusTo(val far: Boolean) : Cmd()
    data class Wb(val preset: WbPreset) : Cmd()
    data class Stabilize(val on: Boolean) : Cmd()
    data class SpokenReplies(val on: Boolean) : Cmd()
    object OpenGallery : Cmd()
    object Help : Cmd()
    data class Query(val what: String) : Cmd()
    data class Chat(val kind: String) : Cmd()
    object Unknown : Cmd()
}

/** Offline, rule-based command understanding. No network, no model. */
object Commands {

    private val words = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    fun normalize(raw: String): String {
        var t = raw.lowercase(Locale.ROOT).replace("-", " ").replace("+", " plus ")
        t = t.replace(Regex("[^a-z0-9./ ]"), " ")
        t = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { w -> words[w]?.toString() ?: w }
        // "twenty five" -> 20 5 -> 25
        t = t.replace(Regex("\\b([2-9]0) ([1-9])\\b")) { m ->
            (m.groupValues[1].toInt() + m.groupValues[2].toInt()).toString()
        }
        // "four hundred" / "one hundred and fifty"
        t = t.replace(Regex("(\\d+) hundred(?: and)?(?: (\\d+))?")) { m ->
            (m.groupValues[1].toInt() * 100 + (m.groupValues[2].toIntOrNull() ?: 0)).toString()
        }
        t = t.replace(Regex("(\\d+) thousand")) { m -> (m.groupValues[1].toInt() * 1000).toString() }
        t = t.replace(Regex("(\\d+) point (\\d+)")) { m -> "${m.groupValues[1]}.${m.groupValues[2]}" }
        return t.trim()
    }

    fun parse(raw: String): Cmd {
        val t = normalize(raw)
        if (t.isEmpty()) return Cmd.Unknown

        fun has(vararg k: String) = k.any { Regex("\\b$it\\b").containsMatchIn(t) }
        fun number(): Float? = Regex("\\d+(?:\\.\\d+)?").find(t)?.value?.toFloatOrNull()
        fun off() = has("off", "disable", "hide", "no", "without")
        fun on() = has("on", "enable", "show", "with")

        // ---- talking about the app itself
        if (has("stop talking", "be quiet", "mute", "voice off", "no voice", "silent")) return Cmd.SpokenReplies(false)
        if (has("unmute", "speak", "voice on", "talk to me", "read out")) return Cmd.SpokenReplies(true)
        if (has("help", "what can you do", "commands", "what can i say")) return Cmd.Help
        if (has("who are you", "your name", "what are you")) return Cmd.Chat("who")
        val short = t.split(" ").size <= 3
        if (short && has("thank\\w*", "good job", "nice", "great", "awesome")) return Cmd.Chat("thanks")
        if (short && has("hello", "hi", "hey", "good morning", "good evening", "good afternoon")) return Cmd.Chat("hello")

        // ---- questions
        val ask = has("what", "what's", "whats", "which", "how", "is", "are", "tell", "check", "show")
        if (ask) {
            when {
                has("light", "lighting", "bright\\w*", "dark", "exposure") && !has("set", "change") -> return Cmd.Query("light")
                has("focus\\w*", "sharp") -> return Cmd.Query("focus")
                has("iso") -> return Cmd.Query("iso")
                has("shutter") -> return Cmd.Query("shutter")
                has("lens", "camera") -> return Cmd.Query("lens")
                has("mode") -> return Cmd.Query("mode")
                has("zoom") -> return Cmd.Query("zoom")
                has("settings", "status", "setup") -> return Cmd.Query("settings")
            }
        }

        // ---- modes
        if (has("raw video", "raw movie")) return Cmd.SetMode(Mode.RAW_VIDEO)
        if (has("video mode", "movie mode", "switch to video", "go to video")) return Cmd.SetMode(Mode.VIDEO)
        if (has("photo mode", "picture mode", "camera mode", "switch to photo", "go to photo")) return Cmd.SetMode(Mode.PHOTO)

        // ---- recording / shooting
        if (has("stop", "end", "finish", "pause")) return Cmd.StopRec
        if (has("record\\w*", "video", "movie", "film\\w*")) return Cmd.StartRec
        if (has("photo\\w*", "picture\\w*", "pic", "pics", "shot", "capture", "snap", "click", "cheese", "shoot", "take one")) return Cmd.Photo

        // ---- camera / lens / zoom
        if (has("front", "selfie")) return Cmd.Facing(false)
        if (has("back camera", "rear", "back")) return Cmd.Facing(true)
        if (has("ultra wide", "ultrawide", "ultra")) return Cmd.LensRatio(0.5f)
        if (has("telephoto", "tele", "zoom lens", "longest")) return Cmd.LensRatio(-1f)
        if (has("main", "normal lens", "standard lens", "wide")) return Cmd.LensRatio(1f)
        if (has("zoom\\w*")) {
            val n = number()
            return when {
                has("out") -> Cmd.ZoomBy(1f / 1.5f)
                has("in", "closer") -> Cmd.ZoomBy(1.5f)
                has("max", "maximum", "full") -> Cmd.ZoomTo(100f)
                has("reset", "normal", "default") -> Cmd.ZoomTo(1f)
                n != null -> Cmd.ZoomTo(n)
                else -> Cmd.Unknown
            }
        }
        if (has("lens")) number()?.let { return Cmd.LensRatio(it) }

        // ---- flash / format / look / timer / grid
        if (has("torch", "flashlight")) return Cmd.Flash(if (off()) FlashMode.OFF else FlashMode.TORCH)
        if (has("flash")) return Cmd.Flash(when {
            off() -> FlashMode.OFF
            has("auto", "automatic") -> FlashMode.AUTO
            else -> FlashMode.ON
        })
        if (has("raw plus", "raw and", "raw jpeg", "raw j peg", "raw jpg", "both formats", "both")) return Cmd.Fmt(Format.RAW_JPEG)
        if (has("raw", "dng")) return Cmd.Fmt(Format.RAW)
        if (has("jpeg", "jpg", "j peg")) return Cmd.Fmt(Format.JPEG)
        if (has("natural", "flat", "unprocessed", "no processing", "no filter", "plain")) return Cmd.Natural(true)
        if (has("standard", "processed", "enhanced", "normal processing")) return Cmd.Natural(false)
        if (has("timer")) return Cmd.Timer(if (off()) 0 else (number()?.toInt() ?: 3))
        if (has("grid")) return Cmd.Grid(!off())
        if (has("stabili\\w*")) return Cmd.Stabilize(!off())

        // ---- manual controls
        if (has("auto exposure", "automatic exposure", "exposure auto", "auto iso", "auto mode", "go auto")) return Cmd.AutoExposure
        if (has("iso") && has("auto", "automatic")) return Cmd.AutoExposure
        if (has("iso")) return number()?.let { Cmd.Iso(it.toInt()) } ?: Cmd.Unknown
        if (has("shutter")) {
            val frac = Regex("(\\d+)\\s*(?:/|over|by|slash)\\s*(\\d+)").find(t)
            if (frac != null) {
                val a = frac.groupValues[1].toDouble(); val b = frac.groupValues[2].toDouble()
                if (b > 0) return Cmd.Shutter((1e9 * a / b).toLong())
            }
            val n = number() ?: return Cmd.Unknown
            return if (has("second\\w*", "sec", "secs")) Cmd.Shutter((n * 1e9).toLong())
            else Cmd.Shutter((1e9 / maxOf(n, 1f)).toLong())
        }
        if (has("manual focus")) return Cmd.ManualFocus(true)
        if (has("auto focus", "autofocus")) return Cmd.ManualFocus(false)
        if (has("focus\\w*")) {
            if (has("infinity", "far", "distant")) return Cmd.FocusTo(true)
            if (has("close", "near", "macro")) return Cmd.FocusTo(false)
        }
        if (has("exposure", "brightness", "ev", "brighter", "darker")) {
            val neg = has("minus", "down", "darker", "decrease", "lower", "reduce", "less")
            val amount = if (has("half")) 0.5f else (number() ?: 1f)
            val relative = has("more", "increase", "decrease", "brighter", "darker", "up", "down", "lower", "raise", "reduce", "less")
            return Cmd.Ev(if (neg) -amount else amount, relative)
        }
        if (has("daylight", "sunny", "sun")) return Cmd.Wb(WbPreset.DAYLIGHT)
        if (has("cloudy", "overcast")) return Cmd.Wb(WbPreset.CLOUDY)
        if (has("tungsten", "incandescent", "indoor", "bulb")) return Cmd.Wb(WbPreset.TUNGSTEN)
        if (has("fluorescent", "tube light", "office")) return Cmd.Wb(WbPreset.FLUORESCENT)
        if (has("white balance") && has("auto", "automatic")) return Cmd.Wb(WbPreset.AUTO)

        if (has("gallery", "last photo", "last picture", "open last", "last video")) return Cmd.OpenGallery
        return Cmd.Unknown
    }
}
