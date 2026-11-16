package com.titan.core.domain.gamification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PetNicknameTest {

    @Test
    fun `a plain name is kept as typed`() {
        assertEquals(PetNicknameResult.Valid("Brick"), PetNickname.validate("Brick"))
    }

    @Test
    fun `surrounding whitespace is trimmed, not refused`() {
        assertEquals(PetNicknameResult.Valid("Brick"), PetNickname.validate("  Brick \n"))
    }

    @Test
    fun `a blank name is refused`() {
        assertEquals(PetNicknameResult.Blank, PetNickname.validate(""))
        assertEquals(PetNicknameResult.Blank, PetNickname.validate("   \t "))
    }

    @Test
    fun `twenty-four characters fit and twenty-five do not`() {
        val longest = "a".repeat(PetNickname.MAX_LENGTH)

        assertEquals(PetNicknameResult.Valid(longest), PetNickname.validate(longest))
        assertEquals(PetNicknameResult.TooLong, PetNickname.validate(longest + "a"))
    }

    @Test
    fun `length is counted in characters as the user sees them`() {
        // Each of these is one character to the person typing it and two UTF-16 units.
        val emoji = "💪".repeat(PetNickname.MAX_LENGTH)

        assertEquals(PetNicknameResult.Valid(emoji), PetNickname.validate(emoji))
    }

    @Test
    fun `a control character inside the name is refused`() {
        assertEquals(PetNicknameResult.ControlCharacters, PetNickname.validate("Bri\nck"))
        assertEquals(PetNicknameResult.ControlCharacters, PetNickname.validate("Bri\u0000ck"))
        assertEquals(PetNicknameResult.ControlCharacters, PetNickname.validate("Bri\tck"))
    }

    @Test
    fun `a stored name that fails the rule reads as no name`() {
        assertEquals("Brick", PetNickname.orNull(" Brick "))
        assertNull(PetNickname.orNull(null))
        assertNull(PetNickname.orNull(""))
        assertNull(PetNickname.orNull("a".repeat(10_000)))
    }
}
