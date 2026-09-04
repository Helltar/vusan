package com.helltar.vusan.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolActivityTest {

    // literal names mirror what Koog reports for each @Tool method; if a tool is renamed, the map's
    // method reference follows automatically but this test fails, flagging the runtime-name change.
    @Test
    fun `maps a tool to what it is busy with`() {
        assertEquals(ToolActivity.WRITING, toolActivityFor("sendMessage"))
        assertEquals(ToolActivity.SEARCHING_WEB, toolActivityFor("webSearch"))
        assertEquals(ToolActivity.SEARCHING_WEB, toolActivityFor("metaSearch"))
        assertEquals(ToolActivity.READING_PAGE, toolActivityFor("extractPageContent"))
        assertEquals(ToolActivity.RUNNING_CODE, toolActivityFor("runCommand"))
        assertEquals(ToolActivity.RUNNING_CODE, toolActivityFor("writeWorkspaceFile"))
        assertEquals(ToolActivity.LOOKING_AT_IMAGE, toolActivityFor("describeImage"))
        assertEquals(ToolActivity.WATCHING_VIDEO, toolActivityFor("describeVideo"))
        assertEquals(ToolActivity.DRAWING, toolActivityFor("generateImage"))
        assertEquals(ToolActivity.SEARCHING_IMAGES, toolActivityFor("searchImages"))
        assertEquals(ToolActivity.SEARCHING_GIF, toolActivityFor("searchGif"))
        assertEquals(ToolActivity.DOWNLOADING_VIDEO, toolActivityFor("downloadVideo"))
        assertEquals(ToolActivity.DOWNLOADING_AUDIO, toolActivityFor("playFullTrack"))
        assertEquals(ToolActivity.SENDING_FILE, toolActivityFor("sendFile"))
        assertEquals(ToolActivity.SENDING_FILE, toolActivityFor("sendFromWorkspace"))
        assertEquals(ToolActivity.SPEAKING, toolActivityFor("speakWithVoice"))
        assertEquals(ToolActivity.REMEMBERING, toolActivityFor("rememberAboutMe"))
        assertEquals(ToolActivity.MANAGING_TASKS, toolActivityFor("scheduleTask"))
    }

    // an instant tool is left unmapped on purpose: its caption would flash by unread.
    @Test
    fun `instant and unknown tools name no activity`() {
        assertNull(toolActivityFor("setReaction"))
        assertNull(toolActivityFor("createPoll"))
        assertNull(toolActivityFor("getExchangeRate"))
        assertNull(toolActivityFor("toolThatNeverExisted"))
    }
}
