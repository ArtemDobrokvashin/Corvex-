package com.corvex.mobile.data.network.stomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StompFrameParserTest {

    @Test
    fun `parses CONNECTED frame and ignores heartbeat`() {
        assertNull(StompFrameParser.parse("\n"))

        val raw = "CONNECTED\nversion:1.2\nheart-beat:10000,10000\n\n\u0000"
        val frame = StompFrameParser.parse(raw)!!
        assertEquals("CONNECTED", frame.command)
        assertEquals("1.2", frame.headers["version"])
        assertEquals("", frame.body)
    }

    @Test
    fun `parses MESSAGE frame with json body`() {
        val raw = "MESSAGE\ndestination:/topic/rooms.123\n\n{\"content\":\"hi\"}\u0000"
        val frame = StompFrameParser.parse(raw)!!
        assertEquals("MESSAGE", frame.command)
        assertEquals("/topic/rooms.123", frame.headers["destination"])
        assertEquals("{\"content\":\"hi\"}", frame.body)
    }
}
