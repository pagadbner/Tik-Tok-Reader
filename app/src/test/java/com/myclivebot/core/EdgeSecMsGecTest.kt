package com.myclivebot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EdgeSecMsGecTest {
    @Test
    fun matchesMicrosoftEdgeV2ReferenceVector() {
        assertEquals(
            "3575017BE6BA2CA8-B3BA8420B435DDB0C48BFDAE783DEA42F2B361F94D0D625A231155D76B5CE4D2",
            EdgeSecMsGec.generate(1_786_536_000L)
        )
    }

    @Test
    fun tokenIsStableWithinFiveMinuteWindowAndChangesAtNextWindow() {
        val first = EdgeSecMsGec.generate(1_786_536_000L)
        assertEquals(first, EdgeSecMsGec.generate(1_786_536_299L))
        assertNotEquals(first, EdgeSecMsGec.generate(1_786_536_300L))
    }
}
