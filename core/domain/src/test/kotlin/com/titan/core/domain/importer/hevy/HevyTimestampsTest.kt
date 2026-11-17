package com.titan.core.domain.importer.hevy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Hevy's dates in every shape and language its app and website are known to write them, each
 * the same instant: the first of September or the twentieth of January, as the samples have it.
 */
class HevyTimestampsTest {

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    private fun assertReads(
        expected: String,
        vararg texts: String,
    ) {
        texts.forEach { text -> assertEquals(text, millis(expected), HevyTimestamps.parse(text, ZoneOffset.UTC)) }
    }

    @Test
    fun `the app's day, month word and year, in every language it writes`() {
        assertReads(
            "2026-09-01T17:40:00Z",
            "1 Sep 2026, 17:40",
            "01 Sept 2026, 17:40",
            "1 Sept. 2026, 17:40",
            "1 sept 2026, 17:40",
            "1 sept. 2026, 17:40",
            "1 set 2026, 17:40",
            "1 de set. de 2026, 17:40",
            "1 de set. 2026, 17:40",
            "1 Eyl 2026 17:40",
            "1 Eyl 2026, 17:40",
            "1 сент. 2026 г., 17:40",
            "1 wrz 2026, 17:40",
            "1 9月 2026, 17:40",
            "1 9월 2026, 17:40",
            "1 سبتمبر 2026, 17:40",
            "1 सित॰ 2026, 17:40",
        )
    }

    @Test
    fun `every month reads in the languages whose words differ most`() {
        val months = mapOf(
            "en" to "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec",
            "de" to "Jan. Feb. März Apr. Mai Juni Juli Aug. Sept. Okt. Nov. Dez.",
            "fr" to "janv. févr. mars avr. mai juin juil. août sept. oct. nov. déc.",
            "it" to "gen feb mar apr mag giu lug ago set ott nov dic",
            "es" to "ene feb mar abr may jun jul ago sept oct nov dic",
            "pt" to "jan. fev. mar. abr. mai. jun. jul. ago. set. out. nov. dez.",
            "ca" to "gen. febr. març abr. maig juny jul. ag. set. oct. nov. des.",
            "pl" to "sty lut mar kwi maj cze lip sie wrz paź lis gru",
            "tr" to "Oca Şub Mar Nis May Haz Tem Ağu Eyl Eki Kas Ara",
            "ru" to "янв. февр. мар. апр. мая июн. июл. авг. сент. окт. нояб. дек.",
            "hi" to "जन॰ फ़र॰ मार्च अप्रैल मई जून जुल॰ अग॰ सित॰ अक्टू॰ नव॰ दिस॰",
        )
        months.forEach { (language, words) ->
            words.split(' ').forEachIndexed { index, word ->
                val month = index + 1
                assertEquals(
                    "$language $word",
                    millis("2026-%02d-09T06:05:00Z".format(month)),
                    HevyTimestamps.parse("9 $word 2026, 06:05", ZoneOffset.UTC),
                )
            }
        }
    }

    @Test
    fun `the website's dates, in the shapes its languages give them`() {
        assertReads(
            "2025-01-20T12:00:00Z",
            "Jan 20, 2025, 12:00 PM",
            "Jan 20, 2025 12:00 PM",
            "Jan 20, 2025, 12:00 PM",
            "20.01.2025, 12:00",
            "2025/01/20 12:00",
            "2025. 1. 20. 오후 12:00",
            "2025年1月20日 12:00",
            "2025年1月20日 中午12:00",
            "20 janv. 2025, 12:00",
            "20 de jan. de 2025, 12:00",
        )
    }

    @Test
    fun `a twelve-hour time with a word for the part of the day reads as the hour it is`() {
        assertReads("2026-10-03T00:30:00Z", "Oct 3, 2026, 12:30 AM", "2026年10月3日 凌晨12:30")
        assertReads("2026-10-03T05:30:00Z", "2026. 10. 3. 오전 5:30", "2026年10月3日 清晨5:30")
        assertReads("2026-10-03T10:20:00Z", "2026年10月3日 上午10:20", "Oct 3, 2026, 10:20 am")
        assertReads("2026-10-03T13:00:00Z", "2026年10月3日 中午1:00", "Oct 3, 2026, 1:00 PM")
        // Canadian English and Latin American Spanish write the period with full stops, and spaces.
        assertReads("2026-10-03T13:00:00Z", "Oct 3, 2026, 1:00 p.m.", "3 oct 2026, 1:00 p. m.")
        assertReads("2026-10-03T00:30:00Z", "Oct 3, 2026, 12:30 a.m.", "3 oct 2026, 12:30 a. m.")
        assertReads("2026-10-03T18:00:00Z", "2026年10月3日 下午6:00", "2026. 10. 3. 오후 6:00")
        assertReads("2026-10-03T20:00:00Z", "2026年10月3日 晚上8:00")
    }

    @Test
    fun `an ISO date, as a spreadsheet re-saves one, still reads`() {
        assertReads("2026-10-03T18:00:00Z", "2026-10-03 18:00:00", "2026-10-03T18:00", "2026-10-03, 18:00")
    }

    @Test
    fun `a wall-clock time is read in the zone given`() {
        assertEquals(
            millis("2026-09-01T21:40:00Z"),
            HevyTimestamps.parse("1 Sep 2026, 17:40", ZoneId.of("America/New_York")),
        )
    }

    @Test
    fun `anything that is not a date Hevy writes is null rather than a guess`() {
        listOf(
            null,
            "",
            "   ",
            "yesterday evening",
            // Year last with slashes: the third of December or the twelfth of March.
            "03/12/2023 11:25",
            // A day with no month.
            "32 Sep 2026, 17:40",
            "30 Feb 2026, 17:40",
            "1 Sep 2026, 25:00",
            "1 Sep 2026, 17:60",
            // A word that is no month, or no part of the day.
            "1 Foo 2026, 17:40",
            "Sep 1, 2026, 5:40 XM",
            "Sep 1, 2026, 13:40 PM",
            "Sep 1, 2026, 0:40 AM",
            "Sep 1, 2026, PM 5:40 PM",
            "1 Sep 2026",
            "2026/13/01 10:00",
        ).forEach { text -> assertNull(text, HevyTimestamps.parse(text, ZoneOffset.UTC)) }
    }

    @Test
    fun `a time with seconds, which Hevy does not write, is read with them rather than refused`() {
        assertReads("2026-09-01T17:40:30Z", "1 Sep 2026, 17:40:30")
    }
}
