package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCodecTest {
    @Test
    fun roundTripsProfiles() {
        val file = ProfilesFile(
            selectedProfileId = "b",
            profiles = listOf(
                ServerProfile("a", "Desk", "http://192.168.1.15:3081", pinSaved = true, lastUsedAt = 5),
                ServerProfile("b", "Laptop", "http://10.0.0.5:3081"),
            ),
        )
        val decoded = ProfileCodec.decode(ProfileCodec.encode(file))
        assertEquals(file, decoded)
        assertEquals("b", decoded?.selected()?.id)
    }

    @Test
    fun ignoresUnknownFieldsAndMissingDefaults() {
        val decoded = ProfileCodec.decode(
            """{"version":9,"profiles":[{"id":"x","name":"n","baseUrl":"http://10.0.0.1:3081","future":true}],"extra":1}""",
        )
        assertEquals(1, decoded?.profiles?.size)
        assertEquals("n", decoded?.profiles?.first()?.name)
        assertEquals(false, decoded?.profiles?.first()?.pinSaved)
    }

    @Test
    fun returnsNullForBrokenJson() {
        assertNull(ProfileCodec.decode("not json"))
        assertNull(ProfileCodec.decode("{\"profiles\": 3}"))
    }
}
