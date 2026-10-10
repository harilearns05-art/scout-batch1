package com.highwayscout

// All spoken sentences live here. Language index: 0 English, 1 Hindi.
object Phrases {
    val names = listOf("English", "हिन्दी")
    val codes = listOf("en", "hi")

    fun label(kind: String) = when (kind) {
        "bump" -> "SPEED BREAKER"
        "slow" -> "SLOW-DOWN ZONE"
        "brake" -> "HARD BRAKING SPOT"
        "turn" -> "SHARP TURN"
        "gate" -> "GATE"
        "junction" -> "JUNCTION"
        "slippery" -> "SLIPPERY ROAD"
        "water" -> "WATER ON ROAD"
        else -> "POTHOLE"
    }

    fun name(cat: String, l: Int): String = if (l == 1) when (cat) {
        "bump" -> "स्पीड ब्रेकर"
        "slow" -> "धीमी गति वाला क्षेत्र"
        "brake" -> "तेज़ ब्रेक वाली जगह"
        "turn" -> "तेज़ मोड़"
        "gate" -> "गेट"
        "junction" -> "चौराहा"
        "slippery" -> "फिसलन भरी सड़क"
        "water" -> "सड़क पर पानी"
        else -> "गड्ढा"
    } else when (cat) {
        "bump" -> "speed breaker"
        "slow" -> "slow-down zone"
        "brake" -> "hard-braking spot"
        "turn" -> "sharp turn"
        "gate" -> "gate"
        "junction" -> "junction"
        "slippery" -> "slippery road"
        "water" -> "water on road"
        else -> "pothole"
    }

    fun alert(kind: String, d: Int, l: Int): String = if (l == 1) when (kind) {
        "bump" -> "सावधान! $d मीटर आगे स्पीड ब्रेकर है। कृपया गाड़ी धीमी करें।"
        "slow" -> "$d मीटर आगे गाड़ियां धीमी होती हैं। कृपया गाड़ी धीमी करें।"
        "brake" -> "$d मीटर आगे लोग तेज़ ब्रेक लगाते हैं। रुकने के लिए तैयार रहें।"
        "turn" -> "$d मीटर आगे तेज़ मोड़ है। कृपया गाड़ी धीमी करें।"
        "gate" -> "$d मीटर आगे गेट है।"
        "junction" -> "$d मीटर आगे चौराहा है। आड़े आते वाहनों का ध्यान रखें।"
        "slippery" -> "$d मीटर आगे सड़क फिसलन भरी है। धीमे चलें, अचानक ब्रेक न लगाएं।"
        "water" -> "$d मीटर आगे सड़क पर पानी है। कृपया गाड़ी धीमी करें।"
        else -> "सावधान! $d मीटर आगे गहरा गड्ढा है। कृपया गाड़ी धीमी करें और दाईं ओर चलें।"
    } else when (kind) {
        "bump" -> "Alert! Speed breaker $d meters ahead. Please slow down."
        "slow" -> "Vehicles slow down $d meters ahead. Please slow down."
        "brake" -> "Drivers brake hard $d meters ahead. Be ready to stop."
        "turn" -> "Sharp turn $d meters ahead. Please slow down."
        "gate" -> "Gate $d meters ahead."
        "junction" -> "Junction $d meters ahead. Watch for crossing vehicles."
        "slippery" -> "Slippery road $d meters ahead. Slow down and do not brake hard."
        "water" -> "Water on the road $d meters ahead. Please slow down."
        else -> "Alert! Deep pothole $d meters ahead. Please slow down and move to the right."
    }

    fun started(l: Int) = if (l == 1) "हाईवे स्काउट शुरू हो गया। सुरक्षित चलें।" else "Highway Scout started. Drive safe."
    fun thanks(l: Int) = if (l == 1) "ठीक है। सुरक्षित चलें।" else "Okay. Drive safe."
    fun wake(l: Int) = if (l == 1) "जागिए! कृपया गाड़ी रोककर आराम करें।" else "Wake up! Please stop the vehicle and rest."
    fun marked(l: Int, n: String) = if (l == 1) "यहाँ $n दर्ज किया गया।" else "Marked $n here."
    fun awake(l: Int, m: Int) = if (l == 1)
        "आप $m मिनट से गाड़ी चला रहे हैं। क्या आप जाग रहे हैं? मैं जाग रहा हूँ बटन दबाएँ। नींद आ रही हो तो रुककर आराम करें।"
    else "You have been driving for $m minutes. Are you awake? Tap I'm awake. If you feel sleepy, please stop and rest."
}
