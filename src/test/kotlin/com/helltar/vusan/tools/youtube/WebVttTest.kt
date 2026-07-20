package com.helltar.vusan.tools.youtube

import kotlin.test.Test
import kotlin.test.assertEquals

class WebVttTest {

    @Test
    fun `strips header, cue ids, and timings`() {
        val vtt =
            """
            WEBVTT
            Kind: captions
            Language: en

            1
            00:00:01.000 --> 00:00:03.000
            hello there

            2
            00:00:03.000 --> 00:00:05.000 align:start position:0%
            this is a test
            """.trimIndent()

        assertEquals("hello there this is a test", parseWebVtt(vtt))
    }

    @Test
    fun `collapses the repeated line of scrolling auto captions`() {
        val vtt =
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            first line

            00:00:03.000 --> 00:00:05.000
            first line
            second line

            00:00:05.000 --> 00:00:07.000
            second line
            third line
            """.trimIndent()

        assertEquals("first line second line third line", parseWebVtt(vtt))
    }

    @Test
    fun `removes karaoke timing tags and decodes entities`() {
        val vtt =
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            <c.colorE5E5E5>rock</c> <00:00:01.500><c> &amp; </c><00:00:02.000><c>roll</c>
            """.trimIndent()

        assertEquals("rock & roll", parseWebVtt(vtt))
    }

    @Test
    fun `returns empty text for a track without cues`() {
        assertEquals("", parseWebVtt("WEBVTT\n\n"))
    }
}
