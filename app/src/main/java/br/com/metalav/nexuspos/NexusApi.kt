package br.com.metalav.nexuspos

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.math.roundToInt
import java.io.IOException
import java.net.URLEncoder

/** Máquina retornada por GET /api/pos/machines (id, identificador_local, tipo). */
data class PosMachine(
    val id: String,
    val identificador_local: String,
    val tipo: String
)

class NexusApi(private val baseUrl: String) {
    private val client = OkHttpClient.Builder().build()

    /**
     * Consulta o preço oficial vigente (canal POS) antes de autorizar.
     * POST /api/payments/price com body: condominio_id, condominio_maquinas_id, service_type (lavadora|secadora), channel, context.
     * @return valor_centavos (a partir de quote.amount em BRL)
     * @throws Exception se HTTP != 200, com message do backend quando possível
     */
    suspend fun fetchPrice(
        condominioId: String,
        condominioMaquinasId: String,
        serviceType: String
    ): Int = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val url = "$base/api/payments/price"
        val bodyJson = JSONObject().apply {
            put("condominio_id", condominioId)
            put("condominio_maquinas_id", condominioMaquinasId)
            put("service_type", serviceType)
            put("channel", "pos")
            put("origin", JSONObject())
            put("context", JSONObject().put("coupon_code", JSONObject.NULL))
        }
        val body = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        Log.d(
            "NEXUS_PRICE",
            "REQ POST $url condominio=$condominioId maquina=$condominioMaquinasId service=$serviceType"
        )

        val response = client.newCall(request).execute()
        val raw = response.body?.string().orEmpty()

        Log.d(
            "NEXUS_PRICE",
            "RESP status=${response.code} url=${response.request.url}"
        )

        if (!response.isSuccessful) {
            Log.e(
                "NEXUS_PRICE",
                "ERROR status=${response.code} body=$raw"
            )
            val message = try {
                JSONObject(raw).optJSONObject("error_v1")?.optString("message")
                    ?: JSONObject(raw).optString("error", "Erro ao consultar preço")
            } catch (_: Exception) {
                "Erro ao consultar preço (${response.code})"
            }
            throw Exception(message)
        }
        val json = JSONObject(raw)
        val quote = json.optJSONObject("quote") ?: throw Exception("Resposta sem quote")
        val amountBrl = quote.optDouble("amount", -1.0)
        if (amountBrl <= 0) throw Exception("Preço inválido na resposta")
        val valorCentavos = (amountBrl * 100).roundToInt()
        Log.d(
            "NEXUS_PRICE",
            "PARSED amount=$amountBrl centavos=$valorCentavos"
        )
        valorCentavos
    }

    /**
     * Lista máquinas do POS para o condomínio. GET /api/pos/machines com header x-pos-serial.
     * @return lista de máquinas (id, identificador_local, tipo: lavadora|secadora)
     */
    suspend fun getMachines(condominioId: String, posSerial: String): List<PosMachine> = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val encoded = URLEncoder.encode(condominioId, "UTF-8")
        val url = "$base/api/pos/machines?condominio_id=$encoded"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("x-pos-serial", posSerial)
            .build()

        Log.d("NEXUS_MACHINES", "REQ GET $url condominio=$condominioId")

        val response = client.newCall(request).execute()
        val raw = response.body?.string().orEmpty()

        Log.d("NEXUS_MACHINES", "RESP status=${response.code} url=${response.request.url}")

        if (!response.isSuccessful) {
            Log.e("NEXUS_MACHINES", "ERROR status=${response.code} body=$raw")
            val msg = try {
                JSONObject(raw).optJSONObject("error_v1")?.optString("message")
                    ?: JSONObject(raw).optString("error", "HTTP ${response.code}")
            } catch (_: Exception) { "HTTP ${response.code}" }
            throw Exception(msg)
        }
        val json = JSONObject(raw)
        val items = json.optJSONArray("items") ?: return@withContext emptyList()
        (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PosMachine(
                id = id,
                identificador_local = obj.optString("identificador_local", ""),
                tipo = obj.optString("tipo", "").lowercase()
            )
        }.also { list ->
            Log.d("NEXUS_MACHINES", "PARSED count=${list.size} ids=${list.map { it.id }.take(5)} tipos=${list.map { it.tipo }}")
        }
    }

    suspend fun getPosStatus(pagamentoId: String): PosStatusResponse = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val encoded = java.net.URLEncoder.encode(pagamentoId, "UTF-8")
        val url = "$base/api/pos/status?pagamento_id=$encoded"
        val req = Request.Builder().url(url).get().build()

        Log.d("NEXUS_STATUS", "REQ GET $url")

        val response = client.newCall(req).execute()
        val raw = response.body?.string().orEmpty()

        Log.d("NEXUS_STATUS", "RESP status=${response.code} url=${response.request.url}")

        if (!response.isSuccessful) {
            Log.e("NEXUS_STATUS", "ERROR status=${response.code} body=$raw")
            return@withContext PosStatusResponse(
                ok = false,
                pagamento = null,
                ciclo = null,
                iot_command = null,
                ui_state = null,
                availability = null,
                error = "HTTP ${response.code}: $raw"
            )
        }
        try {
            val resp = parsePosStatusResponse(JSONObject(raw))
            Log.d("NEXUS_STATUS", "PARSED ui_state=${resp.ui_state} availability=${resp.availability} pagamento_id=${resp.pagamento?.id} ciclo_id=${resp.ciclo?.id}")
            resp
        } catch (e: Exception) {
            Log.e("NEXUS_STATUS", "PARSE_ERROR ${e.message}")
            PosStatusResponse(
                ok = false,
                pagamento = null,
                ciclo = null,
                iot_command = null,
                ui_state = null,
                availability = null,
                error = e.message ?: "Parse error"
            )
        }
    }

    /**
     * GET /api/pos/status?identificador_local=... with header x-pos-serial (machine-level status; backend returns LIVRE when cycle FINALIZADO).
     */
    suspend fun getPosStatusByIdentificadorLocal(posSerial: String, identificadorLocal: String): PosStatusResponse = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val qIdent = URLEncoder.encode(identificadorLocal, "UTF-8")
        val url = "$base/api/pos/status?identificador_local=$qIdent"
        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("x-pos-serial", posSerial)
            .build()

        Log.d("NEXUS_STATUS", "REQ GET $url ident=$identificadorLocal")

        val response = client.newCall(req).execute()
        val raw = response.body?.string().orEmpty()

        Log.d("NEXUS_STATUS", "RESP status=${response.code} url=${response.request.url}")

        if (!response.isSuccessful) {
            Log.e("NEXUS_STATUS", "ERROR status=${response.code} body=$raw")
            return@withContext PosStatusResponse(
                ok = false,
                pagamento = null,
                ciclo = null,
                iot_command = null,
                ui_state = null,
                availability = null,
                error = "HTTP ${response.code}: $raw"
            )
        }
        try {
            val resp = parsePosStatusResponse(JSONObject(raw))
            Log.d("NEXUS_STATUS", "PARSED ui_state=${resp.ui_state} availability=${resp.availability} pagamento_id=${resp.pagamento?.id} ciclo_id=${resp.ciclo?.id}")
            resp
        } catch (e: Exception) {
            Log.e("NEXUS_STATUS", "PARSE_ERROR ${e.message}")
            PosStatusResponse(
                ok = false,
                pagamento = null,
                ciclo = null,
                iot_command = null,
                ui_state = null,
                availability = null,
                error = e.message ?: "Parse error"
            )
        }
    }

    /**
     * POST /api/payments/confirm — chamado pelo provider/webhook; app pode usar em fluxo manual.
     * Body: payment_id, provider (stone|asaas), provider_ref, result (approved|failed), channel, origin.
     */
    suspend fun confirm(
        paymentId: String,
        provider: String,
        providerRef: String,
        result: String
    ): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val url = "$base/api/payments/confirm"
        val bodyJson = JSONObject().apply {
            put("payment_id", paymentId)
            put("provider", provider.lowercase())
            put("provider_ref", providerRef)
            put("result", result.lowercase())
            put("channel", "pos")
            put("origin", JSONObject().put("pos_device_id", JSONObject.NULL).put("user_id", JSONObject.NULL))
        }
        val body = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        Log.d("NEXUS_CONFIRM", "REQ POST $url payment_id=$paymentId provider=$provider result=$result")
        val response = client.newCall(req).execute()
        val raw = response.body?.string().orEmpty()
        Log.d("NEXUS_CONFIRM", "RESP status=${response.code} body=$raw")
        if (!response.isSuccessful) {
            Log.e("NEXUS_CONFIRM", "ERROR status=${response.code} body=$raw")
            val msg = try {
                val v1 = JSONObject(raw).optJSONObject("error_v1")
                val code = v1?.optString("code", "")?.takeIf { it.isNotBlank() }
                val message = v1?.optString("message", "")?.takeIf { it.isNotBlank() } ?: JSONObject(raw).optString("error", raw)
                if (code != null) "[$code] $message" else message
            } catch (_: Exception) { raw }
            throw Exception(msg)
        }
        raw
    }

    /**
     * POST /api/payments/execute-cycle — cria ciclo + comando IoT; idempotente.
     * Body: idempotency_key, payment_id, condominio_maquinas_id, channel, origin.
     * Chamar somente após confirm retornar OK (pagamento PAGO).
     */
    suspend fun executeCycle(
        idempotencyKey: String,
        paymentId: String,
        condominioMaquinasId: String
    ): String = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val url = "$base/api/payments/execute-cycle"
        val bodyJson = JSONObject().apply {
            put("idempotency_key", idempotencyKey)
            put("payment_id", paymentId)
            put("condominio_maquinas_id", condominioMaquinasId)
            put("channel", "pos")
            put("origin", JSONObject().put("pos_device_id", JSONObject.NULL).put("user_id", JSONObject.NULL))
        }
        val body = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        Log.d("NEXUS_EXEC", "REQ POST $url payment_id=$paymentId maquina=$condominioMaquinasId")
        val response = client.newCall(req).execute()
        val raw = response.body?.string().orEmpty()
        Log.d("NEXUS_EXEC", "RESP status=${response.code} body=$raw")
        if (!response.isSuccessful) {
            Log.e("NEXUS_EXEC", "ERROR status=${response.code} body=$raw")
            val msg = try {
                val v1 = JSONObject(raw).optJSONObject("error_v1")
                val code = v1?.optString("code", "")?.takeIf { it.isNotBlank() }
                val message = v1?.optString("message", "")?.takeIf { it.isNotBlank() } ?: JSONObject(raw).optString("error", raw)
                if (code != null) "[$code] $message" else message
            } catch (_: Exception) { raw }
            throw Exception(msg)
        }
        raw
    }

    private fun parsePosStatusResponse(obj: JSONObject): PosStatusResponse {
        val ok = obj.optBoolean("ok", false)
        val pagamento = obj.optJSONObject("pagamento")?.let { parsePagamento(it) }
        val ciclo = obj.optJSONObject("ciclo")?.let { parseCiclo(it) }
        val iotCommand = obj.optJSONObject("iot_command")?.let { parseIotCommand(it) }
        val uiState = obj.optString("ui_state", "").takeIf { it.isNotBlank() }
        val availability = obj.optString("availability", "").takeIf { it.isNotBlank() }
        val error = obj.optString("error", "").takeIf { it.isNotBlank() }
        return PosStatusResponse(
            ok = ok,
            pagamento = pagamento,
            ciclo = ciclo,
            iot_command = iotCommand,
            ui_state = uiState,
            availability = availability,
            error = error
        )
    }

    private fun parsePagamento(obj: JSONObject): Pagamento = Pagamento(
        id = obj.optString("id", ""),
        status = obj.optString("status", "").takeIf { it.isNotBlank() },
        valor_centavos = obj.optInt("valor_centavos", 0).takeIf { obj.has("valor_centavos") },
        metodo = obj.optString("metodo", "").takeIf { it.isNotBlank() },
        created_at = obj.optString("created_at", "").takeIf { it.isNotBlank() },
        expires_at = obj.optString("expires_at", "").takeIf { it.isNotBlank() }
    )

    private fun parseCiclo(obj: JSONObject): Ciclo = Ciclo(
        id = obj.optString("id", ""),
        status = obj.optString("status", "").takeIf { it.isNotBlank() },
        condominio_maquinas_id = obj.optString("condominio_maquinas_id", "").takeIf { it.isNotBlank() },
        condominio_id = obj.optString("condominio_id", "").takeIf { it.isNotBlank() }
    )

    private fun parseIotCommand(obj: JSONObject): IotCommand = IotCommand(
        id = obj.optString("id", ""),
        cmd_id = obj.optString("cmd_id", "").takeIf { it.isNotBlank() },
        status = obj.optString("status", "").takeIf { it.isNotBlank() },
        created_at = obj.optString("created_at", "").takeIf { it.isNotBlank() },
        expires_at = obj.optString("expires_at", "").takeIf { it.isNotBlank() },
        correlation_id = obj.opt("correlation_id")
    )
}
