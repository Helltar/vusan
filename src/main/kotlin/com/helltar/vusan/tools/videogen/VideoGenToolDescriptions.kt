package com.helltar.vusan.tools.videogen

internal object VideoGenToolDescriptions {

    const val GENERATE_VIDEO =
        "Make a short video with sound and send it: from a description alone, from a picture it opens on, toward a picture it ends on, or featuring people, things or a style from reference pictures. " +
                "Use it when someone asks for a video, a clip or an animation, or to bring a photo to life. " +
                "Rendering takes from half a minute to a few minutes."

    const val PROMPT =
        "What happens in the video: the scene, the motion, the camera, and any sound or speech. " +
                "Name each reference picture as `<IMAGE_0>`, `<IMAGE_1>`, and so on, in the order of `references`, and yourself as `<SELF>` when `withYourself` is on."

    const val START_FRAME =
        "A picture the video opens on and brings to life, by label; the video takes its shape. " +
                "Leave it out for a video made from the description alone."

    const val END_FRAME = "A picture the video ends on, by label."

    const val REFERENCES =
        "Pictures of people, things or a style for the video to feature without opening on them, by label. " +
                "The prompt names them as `<IMAGE_0>`, `<IMAGE_1>`, and so on."

    const val WITH_YOURSELF = "Put yourself in the video, looking the way you do in your own photo; the prompt names you as `<SELF>`."

    const val ASPECT_RATIO =
        "The frame: `16:9` (the default), `9:16`, `1:1`, `4:3`, `3:4`, `3:2`, `2:3`, `21:9` or `5:2`. " +
                "A start frame decides it instead."

    const val SECONDS = "Length in seconds, from `1` to `15`."

    const val SOUND = "`false` for a silent video; it has sound by default."

    const val SEND = "`false` keeps the video for a later call, such as `extendVideo`, without sending it."

    const val EDIT_VIDEO =
        "Change an existing video as described and send the result: restyle it, change the weather or the time of day, add or remove something. " +
                "It keeps its length; the source has to be an MP4 clip of under nine seconds."

    const val EDIT_PROMPT = "The change to make."

    const val EDIT_SOURCE =
        "The video to change, by label: one from the chat, or one made earlier in this turn. " +
                "The video attached to the request when left out."

    const val EDIT_SEND = "`false` keeps the result for a later call without sending it."

    const val EXTEND_VIDEO =
        "Continue a video from its last frame and send the original and the continuation as one video. " +
                "Use it to make a clip longer, or to carry on what a video made earlier was doing."

    const val EXTEND_PROMPT = "What happens next."

    const val EXTEND_SOURCE =
        "The video to continue, by label: one from the chat, or one made earlier in this turn. " +
                "The video attached to the request when left out."

    const val EXTEND_SECONDS = "Seconds to add, from `2` to `10`."

    const val EXTEND_SEND = "`false` keeps the result for a later call without sending it."
}
