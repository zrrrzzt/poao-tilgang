package no.nav.poao_tilgang.controller

import io.kotest.matchers.shouldBe
import no.nav.poao_tilgang.domain.AdGruppe
import no.nav.poao_tilgang.test_util.IntegrationTest
import no.nav.poao_tilgang.utils.RestUtils.toJsonRequestBody
import org.junit.jupiter.api.Test
import java.util.*

class AdGruppeControllerIntegrationTest : IntegrationTest() {

	@Test
	fun `hentAdGrupperForNavAnsatt - should return 401 when not authenticated`() {
		val response = sendRequest(
			method = "POST",
			path = "/api/v1/ad-gruppe",
			body = """{"navIdent": "Z1234"}"""".toJsonRequestBody()
		)

		response.code shouldBe 401
	}

	@Test
	fun `hentAdGrupperForNavAnsatt - should return 403 when not machine-to-machine request`() {
		val response = sendRequest(
			method = "POST",
			path = "/api/v1/ad-gruppe",
			body = """{"navIdent": "Z1234"}"""".toJsonRequestBody(),
			headers = mapOf("Authorization" to "Bearer ${oAuthServer.issueAzureAdToken()}")
		)

		response.code shouldBe 403
	}

	@Test
	fun `hentAdGrupperForNavAnsatt - should return 200 with correct response`() {
		mockMicrosoftGraphHttpClient.enqueueHentAzureAdIdResponse(
			UUID.randomUUID()
		)

		mockMicrosoftGraphHttpClient.enqueueHentAdGrupperResponse(
			listOf(AdGruppe(id = UUID.fromString("a0036e11-5658-4d2d-aa6b-7056bdb4e758"), name = "TODO"))
		)

		val response = sendRequest(
			method = "POST",
			path = "/api/v1/ad-gruppe",
			body = """{"navIdent": "Z1234"}"""".toJsonRequestBody(),
			headers = mapOf("Authorization" to "Bearer ${oAuthServer.issueAzureAdM2MToken()}")
		)

		val expectedJson = """
			[{"id":"a0036e11-5658-4d2d-aa6b-7056bdb4e758","name":"TODO"}]
		""".trimIndent()

		response.body?.string() shouldBe expectedJson
		response.code shouldBe 200
	}

}
