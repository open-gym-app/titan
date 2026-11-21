package com.titan.core.domain.importer.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackedRecordTest {

    @Test
    fun `a packed record reads back the fields it was made from, empty ones included`() {
        val fields = listOf("42", "", "Barbell Bench Press", "", "60x10,80x5", "")

        val packed = PackedRecord(fields)

        assertEquals(fields, packed)
        assertEquals(fields.size, packed.size)
        assertEquals("Barbell Bench Press", packed[2])
        assertEquals("", packed[5])
    }

    @Test
    fun `a record of one empty field is one field, and an index past the end is refused`() {
        val packed = PackedRecord(listOf(""))

        assertEquals(listOf(""), packed)
        assertThrows(IndexOutOfBoundsException::class.java) { packed[1] }
        assertThrows(IndexOutOfBoundsException::class.java) { packed[-1] }
    }
}
