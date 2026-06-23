package com.helltar.vusan.telegram

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class HtmlReplyDocumentTest {

    @Test
    fun `embeds the reply body inside the document`() {
        val doc = htmlReplyDocument("<b>hi</b> there")
        assertContains(doc, "<main><b>hi</b> there</main>")
    }

    @Test
    fun `is responsive and theme-aware`() {
        val doc = htmlReplyDocument("text")
        assertContains(doc, """<meta name="viewport" content="width=device-width, initial-scale=1">""")
        assertContains(doc, "color-scheme: light dark")
        assertContains(doc, "@media (prefers-color-scheme: dark)")
    }

    @Test
    fun `blocks scripts via content security policy`() {
        val doc = htmlReplyDocument("<script>alert(1)</script>")
        assertContains(doc, "Content-Security-Policy")
        assertContains(doc, "default-src 'none'")
        // the markup is embedded verbatim; the CSP, not escaping, is what neutralizes it.
        assertTrue("<script>alert(1)</script>" in doc)
    }
}
