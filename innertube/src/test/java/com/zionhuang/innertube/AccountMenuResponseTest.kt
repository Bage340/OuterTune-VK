package com.zionhuang.innertube

import com.zionhuang.innertube.models.response.AccountMenuResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decoding of the account menu across the shapes the profile image comes in. A response without a
 * usable image must still yield the rest of the account details.
 */
@OptIn(ExperimentalSerializationApi::class)
class AccountMenuResponseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun accountInfo(resource: String) =
        javaClass.classLoader!!.getResourceAsStream(resource)!!.bufferedReader().use { it.readText() }
            .let { json.decodeFromString<AccountMenuResponse>(it) }
            .actions[0].openPopupAction.popup.multiPageMenuRenderer
            .header!!.activeAccountHeaderRenderer
            .toAccountInfo()

    @Test
    fun photoPresent_readsFirstThumbnail() {
        val info = accountInfo("account_menu_full.json")
        assertEquals("Test User", info.name)
        assertEquals("test.user@example.com", info.email)
        assertEquals("https://yt3.ggpht.com/example=s108-c-k-c0x00ffffff-no-rj", info.thumbnailUrl)
    }

    @Test
    fun photoFieldMissing_hasNoUrl() {
        val info = accountInfo("account_menu_no_photo_field.json")
        assertEquals("Test User", info.name)
        assertNull(info.thumbnailUrl)
    }

    @Test
    fun photoNull_hasNoUrl() {
        assertNull(accountInfo("account_menu_photo_null.json").thumbnailUrl)
    }

    @Test
    fun thumbnailsKeyMissing_hasNoUrl() {
        assertNull(accountInfo("account_menu_photo_no_thumbnails_key.json").thumbnailUrl)
    }

    @Test
    fun thumbnailsEmpty_hasNoUrl() {
        assertNull(accountInfo("account_menu_photo_empty_thumbnails.json").thumbnailUrl)
    }
}
