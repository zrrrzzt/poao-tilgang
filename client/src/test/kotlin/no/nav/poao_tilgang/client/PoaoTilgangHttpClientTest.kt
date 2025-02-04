package no.nav.poao_tilgang.client

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.beInstanceOf
import no.nav.common.rest.client.RestClient
import no.nav.poao_tilgang.application.test_util.IntegrationTest
import no.nav.poao_tilgang.client.api.BadHttpStatusApiException
import no.nav.poao_tilgang.client.api.NetworkApiException
import no.nav.poao_tilgang.core.domain.AdGruppe
import no.nav.poao_tilgang.core.domain.TilgangType.LESE
import no.nav.poao_tilgang.core.domain.TilgangType.SKRIVE
import no.nav.poao_tilgang.core.provider.AdGruppeProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import java.net.UnknownHostException
import java.time.Duration
import java.util.*

class PoaoTilgangHttpClientTest : IntegrationTest() {

	private val navIdent = "Z1235"
	private val norskIdent = "6456532"
	private val navAnsattId = UUID.randomUUID()

	private val fnr1 = "124253321"
	private val fnr2 = "654756834"


	lateinit var client: PoaoTilgangHttpClient

	@Autowired
	private lateinit var adGruppeProvider: AdGruppeProvider

	@BeforeEach
	fun setup() {
		client = PoaoTilgangHttpClient(
			serverUrl(),
			{ mockOAuthServer.issueAzureAdM2MToken() },
			RestClient.baseClientBuilder().readTimeout(Duration.ofMinutes(15)).build()
		)
	}

	@ParameterizedTest
	@EnumSource(TilgangType::class)
	fun `evaluatePolicy - should evaluate NavAnsattTilgangTilEksternBrukerPolicy V2`(tilgangType: TilgangType) {
		val coreTilgangType = when(tilgangType) {
			TilgangType.LESE -> LESE
			TilgangType.SKRIVE -> SKRIVE
		}
		mockAbacHttpServer.mockPermit(coreTilgangType)
		setupMocks()

		val decision =
			client.evaluatePolicy(NavAnsattTilgangTilEksternBrukerPolicyInput(navAnsattId, tilgangType, norskIdent)).getOrThrow()

		decision shouldBe Decision.Permit
	}

	@Test
	fun `evaluatePolicy - should evaluate NavAnsattTilgangTilModiaPolicy`() {
		mockAdGrupperResponse(
			navIdent,
			navAnsattId,
			listOf(adGruppeProvider.hentTilgjengeligeAdGrupper().modiaGenerell)
		)

		val decision = client.evaluatePolicy(NavAnsattTilgangTilModiaPolicyInput(navAnsattId)).getOrThrow()

		decision shouldBe Decision.Permit
	}

	@Test
	fun `evaluatePolicy - should evaluate EksternBrukerTilgangTilEksternBrukerPolicy`() {
		val decision = client.evaluatePolicy(EksternBrukerTilgangTilEksternBrukerPolicyInput(
			rekvirentNorskIdent = "234",
			ressursNorskIdent = "234"
		)).getOrThrow()

		decision shouldBe Decision.Permit
	}

	@Test
	fun `evaluatePolicy - should evaluate NavAnsattHarTilgangTilNavEnhetPolicy`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				AdGruppe(UUID.randomUUID(), "0000-ga-123"),
				AdGruppe(UUID.randomUUID(), "0000-ga-456")
			)
		)

		mockAbacHttpServer.mockPermitAll()

		val decision = client.evaluatePolicy(NavAnsattTilgangTilNavEnhetPolicyInput(
			navAnsattAzureId = navAnsattId,
			navEnhetId = "0123"
		)).getOrThrow()

		decision shouldBe Decision.Permit
	}

	@Test
	fun `evaluatePolicy - should evaluate NavAnsattHarTilgangTilNavEnhetMedSperrePolicy`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				AdGruppe(UUID.randomUUID(), "0000-ga-123"),
				AdGruppe(UUID.randomUUID(), "0000-ga-456")
			)
		)

		mockAbacHttpServer.mockPermitAll()

		val decision = client.evaluatePolicy(NavAnsattTilgangTilNavEnhetMedSperrePolicyInput(
			navAnsattAzureId = navAnsattId,
			navEnhetId = "0123"
		)).getOrThrow()

		decision shouldBe Decision.Permit
	}

	@Test
	fun `hentAdGrupper - skal hente AD-grupper`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				AdGruppe(UUID.randomUUID(), "0000-ga-123"),
				AdGruppe(UUID.randomUUID(), "0000-ga-456")
			)
		)

		val adGrupper = client.hentAdGrupper(navAnsattId).getOrThrow()

		adGrupper shouldHaveSize 2
		adGrupper.any { it.navn == "0000-ga-123" } shouldBe true
		adGrupper.any { it.navn == "0000-ga-456" } shouldBe true
	}

	@Test
	fun `erSkjermetPerson - skal hente enkelt skjermet person`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				AdGruppe(UUID.randomUUID(), "0000-ga-123"),
				AdGruppe(UUID.randomUUID(), "0000-ga-456")
			)
		)

		mockSkjermetPersonHttpServer.mockErSkjermet(
			mapOf(
				fnr1 to true,
				fnr2 to false
			)
		)

		client.erSkjermetPerson(fnr1).getOrThrow() shouldBe true
		client.erSkjermetPerson(fnr2).getOrThrow() shouldBe false
	}

	@Test
	fun `erSkjermetPerson - skal hente bulk skjermet person`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				AdGruppe(UUID.randomUUID(), "0000-ga-123"),
				AdGruppe(UUID.randomUUID(), "0000-ga-456")
			)
		)

		mockSkjermetPersonHttpServer.mockErSkjermet(
			mapOf(
				fnr1 to true,
				fnr2 to false
			)
		)

		val erSkjermet = client.erSkjermetPerson(listOf(fnr1, fnr2)).getOrThrow()

		erSkjermet[fnr1] shouldBe true
		erSkjermet[fnr2] shouldBe false
	}

	@Test
	fun `skal returnere BadHttpStatusApiException for feilende status`() {
		val badClient = PoaoTilgangHttpClient(serverUrl(), { "" })

		val exception = badClient.erSkjermetPerson("34242").exception
		exception should beInstanceOf<BadHttpStatusApiException>()
		(exception as BadHttpStatusApiException).httpStatus shouldBe 401
		exception.responseBody shouldNotBe null
	}

	@Test
	fun `skal returnere NetworkApiException for netverk feil`() {
		val badClient = PoaoTilgangHttpClient("http://not-a-real-host", { "" })

		val exception = badClient.erSkjermetPerson("34242").exception

		exception should beInstanceOf<NetworkApiException>()
		exception?.cause should beInstanceOf<UnknownHostException>()
	}

	@Test
	fun `evaluatePolicy - should permit NAV_ANSATT_BEHANDLE_STRENGT_FORTROLIG_BRUKERE`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				adGruppeProvider.hentTilgjengeligeAdGrupper().modiaGenerell,
				adGruppeProvider.hentTilgjengeligeAdGrupper().strengtFortroligAdresse
			)
		)

		val decision = client.evaluatePolicy(NavAnsattBehandleStrengtFortroligBrukerePolicyInput(
			navAnsattAzureId = navAnsattId
		)).getOrThrow()

		decision shouldBe Decision.Permit
	}

	@Test
	fun `evaluatePolicy - should deny NAV_ANSATT_BEHANDLE_STRENGT_FORTROLIG_BRUKERE`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				adGruppeProvider.hentTilgjengeligeAdGrupper().modiaGenerell
			)
		)

		val decision = client.evaluatePolicy(NavAnsattBehandleStrengtFortroligBrukerePolicyInput(
			navAnsattAzureId = navAnsattId
		)).getOrThrow()

		decision shouldBe Decision.Deny("NAV-ansatt mangler tilgang til AD-gruppen \"0000-GA-Strengt_Fortrolig_Adresse\"","MANGLER_TILGANG_TIL_AD_GRUPPE")
	}

	@Test
	fun `evaluatePolicy - should permit NAV_ANSATT_BEHANDLE_FORTROLIG_BRUKERE`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				adGruppeProvider.hentTilgjengeligeAdGrupper().modiaGenerell,
				adGruppeProvider.hentTilgjengeligeAdGrupper().fortroligAdresse
			)
		)

		val decision = client.evaluatePolicy(NavAnsattBehandleFortroligBrukerePolicyInput(
			navAnsattAzureId = navAnsattId
		)).getOrThrow()

		decision shouldBe Decision.Permit
	}

	@Test
	fun `evaluatePolicy - should deny NAV_ANSATT_BEHANDLE_FORTROLIG_BRUKERE`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				adGruppeProvider.hentTilgjengeligeAdGrupper().modiaGenerell
			)
		)

		val decision = client.evaluatePolicy(NavAnsattBehandleFortroligBrukerePolicyInput(
			navAnsattAzureId = navAnsattId
		)).getOrThrow()

		decision shouldBe Decision.Deny("NAV-ansatt mangler tilgang til AD-gruppen \"0000-GA-Fortrolig_Adresse\"","MANGLER_TILGANG_TIL_AD_GRUPPE")
	}

	@Test
	fun `evaluatePolicy - should permit NAV_ANSATT_BEHANDLE_SKJERMEDE_PERSONER`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				adGruppeProvider.hentTilgjengeligeAdGrupper().egneAnsatte,
			)
		)

		val decision = client.evaluatePolicy(NavAnsattBehandleSkjermedePersonerPolicyInput(
			navAnsattAzureId = navAnsattId
		)).getOrThrow()

		decision shouldBe Decision.Permit
	}

	@Test
	fun `evaluatePolicy - should deny NAV_ANSATT_BEHANDLE_SKJERMEDE_PERSONER`() {
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				adGruppeProvider.hentTilgjengeligeAdGrupper().modiaGenerell
			)
		)

		val decision = client.evaluatePolicy(NavAnsattBehandleSkjermedePersonerPolicyInput(
			navAnsattAzureId = navAnsattId
		)).getOrThrow()

		decision shouldBe Decision.Deny("NAV-ansatt mangler tilgang til en av AD-gruppene [0000-GA-GOSYS_UTVIDET, 0000-GA-Pensjon_UTVIDET, 0000-GA-Egne_ansatte]", reason="MANGLER_TILGANG_TIL_AD_GRUPPE")
	}

	private fun mockAdGrupperResponse(navIdent: String, navAnsattId: UUID, adGrupper: List<AdGruppe>) {
		mockMicrosoftGraphHttpServer.mockHentAzureIdMedNavIdentResponse(navIdent, navAnsattId)

		mockMicrosoftGraphHttpServer.mockHentNavIdentMedAzureIdResponse(navAnsattId, navIdent)

		mockMicrosoftGraphHttpServer.mockHentAdGrupperForNavAnsatt(navAnsattId, adGrupper.map { it.id })

		mockMicrosoftGraphHttpServer.mockHentAdGrupperResponse(adGrupper)
	}

	private fun setupMocks() {
		mockPdlHttpServer.mockBrukerInfo(
			norskIdent = norskIdent,
			gtKommune = "1234"
		)

		mockSkjermetPersonHttpServer.mockErSkjermet(
			mapOf(
				norskIdent to false
			)
		)

		mockVeilarbarenaHttpServer.mockOppfolgingsenhet(norskIdent, "1234")
		mockAdGrupperResponse(
			navIdent, navAnsattId, listOf(
				AdGruppe(UUID.randomUUID(), "0000-some-group"),
			)
		)

		mockAxsysHttpServer.mockHentTilgangerResponse(navIdent, listOf())
	}

}
