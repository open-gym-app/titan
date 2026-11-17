package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.ExportDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaUpgradeTest {

    @Test
    fun `a document at this version is returned as it is`() {
        val document = sampleDocument()

        assertSame(document, SchemaUpgrade.toCurrent(document))
    }

    @Test
    fun `a version 1 document keeps every row it held and gains the version stamp`() {
        val versionOne = sampleDocument().copy(
            schemaVersion = 1,
            sessionPetClaim = emptyList(),
            pet = emptyList(),
            petState = emptyList(),
            petLoadout = emptyList(),
            petFeed = emptyList(),
            foodSeen = emptyList(),
            petUnlock = emptyList(),
        )

        val upgraded = SchemaUpgrade.toCurrent(versionOne)

        assertEquals(versionOne.copy(schemaVersion = ExportDocument.SCHEMA_VERSION), upgraded)
    }

    @Test
    fun `a version 1 document cannot smuggle in a table its version never had`() {
        val upgraded = SchemaUpgrade.toCurrent(sampleDocument().copy(schemaVersion = 1))

        assertTrue(upgraded.pet.isEmpty())
        assertTrue(upgraded.sessionPetClaim.isEmpty())
        assertTrue(upgraded.petState.isEmpty())
        assertTrue(upgraded.petLoadout.isEmpty())
        assertTrue(upgraded.petFeed.isEmpty())
        assertTrue(upgraded.foodSeen.isEmpty())
        assertTrue(upgraded.petUnlock.isEmpty())
    }

    @Test
    fun `every older version the decoder accepts has a converter`() {
        SchemaUpgrade.READABLE_OLDER_VERSIONS.forEach { version ->
            val upgraded = SchemaUpgrade.toCurrent(sampleDocument().copy(schemaVersion = version))

            assertEquals(ExportDocument.SCHEMA_VERSION, upgraded.schemaVersion)
        }
    }

    @Test
    fun `a version with no converter is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            SchemaUpgrade.toCurrent(sampleDocument().copy(schemaVersion = ExportDocument.SCHEMA_VERSION + 1))
        }
    }
}
