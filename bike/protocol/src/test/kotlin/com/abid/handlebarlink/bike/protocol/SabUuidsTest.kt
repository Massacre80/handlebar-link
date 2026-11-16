package com.abid.handlebarlink.bike.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.matches
import org.junit.Test

/** Every UUID against the text of SAB_PROTOCOL.md §2, so a typo cannot slip in. */
class SabUuidsTest {
    @Test
    fun `meter services and characteristics match the spec`() {
        val expected =
            mapOf(
                SabUuids.VEHICLE_INFO_SERVICE to "4d87b1ea-528b-4798-b002-e2e1442a2e86",
                SabUuids.VEHICLE_TYPE to "5738696d-45b3-475f-81d8-cc685d56ec18",
                SabUuids.DISPLAY_CAPABILITY to "0ad39e0b-b78d-4252-9917-1aa31a915da7",
                SabUuids.SWITCH_TYPE to "ddd14e97-636d-4338-a798-94cf7ce13667",
                SabUuids.CATEGORY_ID to "3f033888-0211-472e-9405-6525d4e4ddc7",
                SabUuids.SERIES_CODE to "5074e0e8-006f-41ac-aa0f-de6e4649a830",
                SabUuids.DESTINATION_ID to "55f7910b-8cda-458b-9b54-8a7da8cd79d4",
                SabUuids.ASSIGNMENT_SERVICE to "f0746e98-61d6-4570-be7e-7635c65c21cb",
                SabUuids.ASSIGNMENT_CONTROL to "4f5fbe6b-474b-4820-833f-95bfbf525d9f",
                SabUuids.DATA_SERVICE to "8536e103-6771-4d4b-9702-287cb5e1340f",
                SabUuids.DATA_CONTROL_POINT to "d2e23443-f1ad-4293-a70b-a9fd163f7b00",
                SabUuids.FUNCTION_SOURCE to "6c060578-d1d9-460a-b86f-eb97f01b2227",
                SabUuids.SECURITY_SERVICE to "592c1017-9c7b-4a35-aba6-f268592fb8fc",
                SabUuids.DFU_SERVICE to "0ea1bd92-5c48-41ad-9b8f-70da1bd08f35",
                SabUuids.DFU_FIRMWARE_VERSION to "32c7720d-440a-47a8-a866-a8b6bea9eeb6",
            )

        expected.forEach { (uuid, text) -> assertThat(uuid.toString()).isEqualTo(text) }
    }

    @Test
    fun `phone-side services use the Bluetooth SIG base UUID`() {
        assertThat(SabUuids.CURRENT_TIME_SERVICE.toString()).isEqualTo("00001805-0000-1000-8000-00805f9b34fb")
        assertThat(SabUuids.CURRENT_TIME.toString()).isEqualTo("00002a2b-0000-1000-8000-00805f9b34fb")
        assertThat(SabUuids.LOCAL_TIME_INFORMATION.toString()).isEqualTo("00002a0f-0000-1000-8000-00805f9b34fb")
        assertThat(SabUuids.CLIENT_CHARACTERISTIC_CONFIG.toString()).isEqualTo("00002902-0000-1000-8000-00805f9b34fb")
    }

    @Test
    fun `the paired CB350's name matches the device name pattern`() {
        // The real meter advertises "HONDA BTU 2040" (paired 2026-11-16).
        assertThat("HONDA BTU 2040").matches(Regex(SabUuids.DEVICE_NAME_PATTERN))
        assertThat("HONDA BTU 2040".startsWith(SabUuids.DEVICE_NAME_PREFIX)).isEqualTo(true)
    }
}
