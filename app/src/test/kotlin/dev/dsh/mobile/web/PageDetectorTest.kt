package dev.dsh.mobile.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDetectorTest {
    @Test
    fun detectsPocketLoginPage() {
        assertTrue(PageDetector.needsPin("""<form method="post" action="/pocket-login">"""))
        assertFalse(PageDetector.needsPin("<title>DeepSeek Harness</title>"))
    }

    @Test
    fun detectsTransitionAndApp() {
        assertTrue(PageDetector.isTransition("<title>DSH Pocket · 正在进入 | opening…</title>"))
        assertFalse(PageDetector.isTransition("<title>DeepSeek Harness</title>"))
        assertTrue(PageDetector.looksLikeDsh("<title>DeepSeek Harness</title>"))
        assertTrue(PageDetector.looksLikeDsh("data-dsh-pocket-polyfill"))
        assertFalse(PageDetector.looksLikeDsh("<title>Router</title>"))
    }
}
