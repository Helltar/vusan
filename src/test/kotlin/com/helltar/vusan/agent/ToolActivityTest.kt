package com.helltar.vusan.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolActivityTest {

    // literal names mirror what Koog reports for each @Tool method; if a tool is renamed, the map's
    // method reference follows automatically but this test fails, flagging the runtime-name change.
    @Test
    fun `maps media-producing tools to their activity`() {
        assertEquals(ToolActivity.PHOTO, toolActivityFor("generateImage"))
        assertEquals(ToolActivity.PHOTO, toolActivityFor("editImage"))
        assertEquals(ToolActivity.PHOTO, toolActivityFor("searchImages"))
        assertEquals(ToolActivity.VIDEO, toolActivityFor("searchGif"))
        assertEquals(ToolActivity.VIDEO, toolActivityFor("downloadVideo"))
        assertEquals(ToolActivity.VOICE, toolActivityFor("speakWithVoice"))
        assertEquals(ToolActivity.DOCUMENT, toolActivityFor("sendFile"))
        assertEquals(ToolActivity.DOCUMENT, toolActivityFor("playFullTrack"))
    }

    @Test
    fun `unmapped tools fall back to text`() {
        assertEquals(ToolActivity.TEXT, toolActivityFor("webSearch"))
        assertEquals(ToolActivity.TEXT, toolActivityFor("describeImage"))
        assertEquals(ToolActivity.TEXT, toolActivityFor("sendMessage"))
    }
}
