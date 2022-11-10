package no.nav.poao_tilgang.client

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import no.nav.poao_tilgang.client.api.ApiResult
import no.nav.poao_tilgang.client.utils.CacheUtils.tryCacheFirstNotNull
import java.lang.IllegalStateException
import java.time.Duration
import java.util.*

class PoaoTilgangCachedClient(
	private val poaoTilgangClient: PoaoTilgangClient,
	private val policyInputToDecisionCache: Cache<PolicyInput, Decision> = Caffeine.newBuilder()
		.expireAfterWrite(Duration.ofMinutes(30))
		.build(),
	private val navAnsattIdToAzureAdGrupperCache: Cache<UUID, List<AdGruppe>> = Caffeine.newBuilder()
		.expireAfterWrite(Duration.ofMinutes(30))
		.build(),
	private val norskIdentToErSkjermetCache: Cache<NorskIdent, Boolean> = Caffeine.newBuilder()
		.expireAfterWrite(Duration.ofMinutes(30))
		.build()
) : PoaoTilgangClient {

	override fun evaluatePolicy(input: PolicyInput): ApiResult<Decision> {
		val decision = tryCacheFirstNotNull(policyInputToDecisionCache, input) {
			val resultat = poaoTilgangClient.evaluatePolicy(input)
			if (resultat.isFailure) return resultat

			return@tryCacheFirstNotNull resultat.get()!!
		}
		return ApiResult.success(decision)
	}

	override fun evaluatePolicies(requests: List<PolicyRequest>): ApiResult<List<PolicyResult>> {
		val uncachedRequests = mutableListOf<PolicyRequest>()
		val cachedResults = requests.map {
			val maybeResult = policyInputToDecisionCache.getIfPresent(it.policyInput)
			if (maybeResult == null) uncachedRequests.add(it)
			return@map if (maybeResult != null) PolicyResult(it.requestId, maybeResult) else null
		}.filterNotNull()

		val apiResult = poaoTilgangClient.evaluatePolicies(uncachedRequests)
		if (apiResult.isFailure) {
			return apiResult
		}
		val policyResults = apiResult.get()!!
		policyResults.forEach {
			val request = requests.find { r -> it.requestId == r.requestId }
				?: throw IllegalStateException("Fant ikke request med requestId=${it.requestId}")
			policyInputToDecisionCache.put(request.policyInput, it.decision)
		}

		return ApiResult.success(cachedResults.plus(policyResults))

	}

	override fun hentAdGrupper(navAnsattAzureId: UUID): ApiResult<List<AdGruppe>> {
		val adGrupper = tryCacheFirstNotNull(navAnsattIdToAzureAdGrupperCache, navAnsattAzureId) {
			val resultat = poaoTilgangClient.hentAdGrupper(navAnsattAzureId)
			if (resultat.isFailure) return resultat

			return@tryCacheFirstNotNull resultat.get()!!
		}
		return ApiResult.success(adGrupper)
	}

	override fun erSkjermetPerson(norskIdent: NorskIdent): ApiResult<Boolean> {
		val erSkjermet = tryCacheFirstNotNull(norskIdentToErSkjermetCache, norskIdent) {
			val resultat = poaoTilgangClient.erSkjermetPerson(norskIdent)
			if (resultat.isFailure) return resultat

			return@tryCacheFirstNotNull resultat.get()!!
		}
		return ApiResult.success(erSkjermet)
	}

	override fun erSkjermetPerson(norskeIdenter: List<NorskIdent>): ApiResult<Map<NorskIdent, Boolean>> {
		val uncachedIdenter = mutableListOf<NorskIdent>()
		val cachedResults = mutableMapOf<NorskIdent, Boolean>()

		norskeIdenter.forEach {
			val maybeResult = norskIdentToErSkjermetCache.getIfPresent(it)
			if (maybeResult == null) uncachedIdenter.add(it)
			else cachedResults[it] = maybeResult
		}

		val apiResult = poaoTilgangClient.erSkjermetPerson(uncachedIdenter)
		if (apiResult.isFailure) {
			return apiResult
		}
		val erSkjermetPersonMap = apiResult.get()!!
		erSkjermetPersonMap.forEach {
			norskIdentToErSkjermetCache.put(it.key, it.value)
		}

		return ApiResult.success(cachedResults.plus(erSkjermetPersonMap))
	}

}
