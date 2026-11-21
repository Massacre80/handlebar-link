package com.abid.handlebarlink.carplay.protocol.location

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The NMEA pair against DiPlay's own vectors (CarLink vendor/diplay
 * `shared/src/test/java/com/shilapi/xcertplay/transport/Iap2LocationClientTest.kt`): the GGA/RMC prefixes for a
 * known fix, the empty-vs-set course, the DDMMYY date, and each sentence's checksum re-computed in the test, not by
 * the code under test (AGENTS: codecs tested against an independent implementation).
 */
class NmeaTest {
    // 12:35:19 UTC on 23 Mar 2026: time 123519.00, date 230326.
    private val at = ZonedDateTime.of(2026, 3, 23, 12, 35, 19, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    // 48°07.0380' N, 011°31.0000' E (the Wikipedia NMEA example coordinate DiPlay's test uses).
    private fun fix(
        latitude: Double = 48.1173,
        longitude: Double = 11.516666666666667,
        altitudeMeters: Double? = null,
        bearingDegrees: Double? = null,
        speedMetersPerSecond: Double? = null,
        accuracyMeters: Double? = null,
    ) = CarPlayLocationFix(
        latitudeDegrees = latitude,
        longitudeDegrees = longitude,
        timestampMillis = at,
        altitudeMeters = altitudeMeters,
        bearingDegrees = bearingDegrees,
        speedMetersPerSecond = speedMetersPerSecond,
        accuracyMeters = accuracyMeters,
    )

    private fun sentences(fix: CarPlayLocationFix) =
        NmeaLocationEncoder.encode(fix).split("\r\n").filter {
            it.isNotEmpty()
        }

    private fun gga(fix: CarPlayLocationFix) =
        sentences(fix)
            .single {
                it.startsWith("\$GPGGA")
            }.substringBefore('*')
            .split(',')

    private fun rmc(fix: CarPlayLocationFix) =
        sentences(fix)
            .single {
                it.startsWith("\$GPRMC")
            }.substringBefore('*')
            .split(',')

    @Test
    fun `the GGA and RMC carry the fix in UTC, as DiPlay encodes them`() {
        val lines = sentences(fix())
        assertThat(lines.size).isEqualTo(2)
        assertThat(lines[0]).startsWith("\$GPGGA,123519.00,4807.0380,N,01131.0000,E,1,08,")
        assertThat(lines[1]).startsWith("\$GPRMC,123519.00,A,4807.0380,N,01131.0000,E,")
    }

    @Test
    fun `each sentence's checksum is the XOR of its body`() {
        for (sentence in sentences(fix(altitudeMeters = 545.4, bearingDegrees = 84.4, speedMetersPerSecond = 22.2))) {
            val star = sentence.indexOf('*')
            assertThat(sentence.substring(star + 1)).isEqualTo(xor(sentence.substring(1, star)))
        }
    }

    @Test
    fun `the RMC course is empty without a bearing and set with one`() {
        assertThat(rmc(fix(bearingDegrees = null))[COURSE]).isEqualTo("")
        assertThat(rmc(fix(bearingDegrees = Double.NaN))[COURSE]).isEqualTo("")
        assertThat(rmc(fix(bearingDegrees = 84.4))[COURSE]).isEqualTo("84.40")
    }

    @Test
    fun `the RMC date is DDMMYY in UTC`() {
        assertThat(rmc(fix())[DATE]).isEqualTo("230326")
    }

    @Test
    fun `southern and western hemispheres flip the letters`() {
        val south = gga(fix(latitude = -48.1173, longitude = -11.516666666666667))
        assertThat(south[3]).isEqualTo("S")
        assertThat(south[5]).isEqualTo("W")
        assertThat(south[2]).isEqualTo("4807.0380")
        assertThat(south[4]).isEqualTo("01131.0000")
    }

    @Test
    fun `HDOP comes from the accuracy, clamped, else 1`() {
        assertThat(gga(fix(accuracyMeters = 25.0))[HDOP]).isEqualTo("5.0")
        assertThat(gga(fix(accuracyMeters = 1.0))[HDOP]).isEqualTo("0.5")
        assertThat(gga(fix(accuracyMeters = 500.0))[HDOP]).isEqualTo("50.0")
        assertThat(gga(fix(accuracyMeters = null))[HDOP]).isEqualTo("1.0")
    }

    @Test
    fun `speed is reported in knots, else zero`() {
        assertThat(rmc(fix(speedMetersPerSecond = 10.0))[SPEED]).isEqualTo("19.44")
        assertThat(rmc(fix(speedMetersPerSecond = null))[SPEED]).isEqualTo("0.00")
    }

    @Test
    fun `the altitude is metres, else zero, and the pair ends with CRLF`() {
        assertThat(gga(fix(altitudeMeters = 545.4))[ALTITUDE]).isEqualTo("545.4")
        assertThat(gga(fix(altitudeMeters = null))[ALTITUDE]).isEqualTo("0.0")
        assertThat(NmeaLocationEncoder.encode(fix())).contains("\r\n")
    }

    private fun xor(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return String.format(Locale.US, "%02X", value)
    }

    private companion object {
        // $GPGGA,time,lat,N,lon,E,quality,sats,HDOP,alt,...
        const val HDOP = 8
        const val ALTITUDE = 9

        // $GPRMC,time,A,lat,N,lon,E,speed,course,date,...
        const val SPEED = 7
        const val COURSE = 8
        const val DATE = 9
    }
}
