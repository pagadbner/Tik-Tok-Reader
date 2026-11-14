package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtwidCookieParserTest {
    @Test fun acceptsPercentEncodedAndPipeValuesWithoutRewriting() {
        val value = "1%7Cabc%7Cdef%7Cghi"
        assertEquals(value, TtwidCookieParser.validate(value))
        assertEquals(value, TtwidCookieParser.fromCookieHeader("other=x; ttwid=$value; path=/"))
    }

    @Test fun acceptsCookiePunctuationWithoutDecoding() {
        val value = "1|abc.def_ghi-123="
        assertEquals(value, TtwidCookieParser.validate(value))
    }

    @Test fun rejectsEmptyShortAndHeaderInjectionValues() {
        assertNull(TtwidCookieParser.validate(null))
        assertNull(TtwidCookieParser.validate("short"))
        assertNull(TtwidCookieParser.validate("validvalue; other=bad"))
        assertNull(TtwidCookieParser.validate("valid\nvalue"))
    }
}
