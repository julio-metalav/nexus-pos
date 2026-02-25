package br.com.metalav.nexuspos

import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.com.metalav.nexuspos.ui.PosStatusScreen
import br.com.metalav.nexuspos.ui.theme.NexusPosTheme
import br.com.metalav.nexuspos.ui.theme.MetaLavOrange
import br.com.metalav.nexuspos.NexusApi
import br.com.metalav.nexuspos.PosStatusResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

// DataStore
private val ComponentActivity.dataStore by preferencesDataStore(name = "pos_config")

private object Keys {
    val BASE_URL = stringPreferencesKey("base_url")
    val POS_SERIAL = stringPreferencesKey("pos_serial")
    val COND_MAQ_ID = stringPreferencesKey("condominio_maquinas_id")
    val CONDOMINIO_ID = stringPreferencesKey("condominio_id")
    val IDENT_LOCAL = stringPreferencesKey("identificador_local")
}

data class PosConfig(
    val baseUrl: String,
    val posSerial: String,
    val condominioMaquinasId: String,
    val condominioId: String,
    val identificadorLocal: String,
    /** Usado apenas na chamada a authorize (não persistido). Se > 0, enviado como valor_centavos; senão fallback 1600 (dev). */
    val valorCentavosForAuthorize: Int = 0
)

data class AuthorizeResult(
    val ok: Boolean,
    val httpCode: Int,
    val correlationId: String?,
    val pagamentoId: String?,
    val pagamentoStatus: String?,
    val reason: String?,
    val message: String?,
    val raw: String
)

sealed class UiState {
    data object Idle : UiState()
    data object Loading : UiState()
    data class Done(val result: AuthorizeResult) : UiState()
    data class Error(val title: String, val details: String? = null) : UiState()
}

enum class Screen { START, CHOOSE_MACHINE, CHOOSE_PAYMENT, STATUS }

class MainActivity : ComponentActivity() {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // BASIC é ok. Se quiser ver body inteiro depois, trocamos pra BODY.
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NexusPosTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PosV0FixedMachineScreen(
                        readConfig = { readConfig() },
                        saveConfig = { cfg -> saveConfig(cfg) },
                        authorize = { c, metodo, onDone, onErr -> authorize(c, metodo, onDone, onErr) }
                    )
                }
            }
        }
    }

    private suspend fun readConfig(): PosConfig {
        val prefs = dataStore.data.first()
        val base = prefs[Keys.BASE_URL] ?: "https://ci.metalav.com.br"
        val serial = prefs[Keys.POS_SERIAL] ?: "POS-LAB-01"
        val cmid = prefs[Keys.COND_MAQ_ID] ?: "COLE-AQUI-O-UUID-DA-MAQUINA"
        val condId = prefs[Keys.CONDOMINIO_ID] ?: ""
        val ident = prefs[Keys.IDENT_LOCAL] ?: "LAV-01"
        return PosConfig(
            baseUrl = base,
            posSerial = serial,
            condominioMaquinasId = cmid,
            condominioId = condId,
            identificadorLocal = ident,
            valorCentavosForAuthorize = 0
        )
    }

    /** Extrai mensagem de erro do body (error_v1.message / error / message) para exibir na UI. Sempre retorna Pair<String, String?>. */
    private fun parseErrorBody(raw: String, httpCode: Int): Pair<String, String?> {
        if (raw.isBlank()) return Pair("HTTP $httpCode", null)
        return try {
            val obj = JSONObject(raw)
            val v1 = obj.optJSONObject("error_v1")
            val code = v1?.optString("code", "")?.takeIf { it.isNotBlank() }
            val message = v1?.optString("message", "")?.takeIf { it.isNotBlank() }
                ?: obj.optString("error", "").takeIf { it.isNotBlank() }
                ?: obj.optString("message", "").takeIf { it.isNotBlank() }
                ?: obj.optString("text", "").takeIf { it.isNotBlank() }
            val title: String = when {
                message != null -> if (code != null) "[$code] $message" else (message ?: "HTTP $httpCode")
                else -> "HTTP $httpCode"
            }
            Pair(title, raw.take(2000))
        } catch (_: Exception) {
            Pair("HTTP $httpCode", raw.take(2000))
        }
    }

    private suspend fun saveConfig(cfg: PosConfig) {
        dataStore.edit { e ->
            e[Keys.BASE_URL] = cfg.baseUrl.trim()
            e[Keys.POS_SERIAL] = cfg.posSerial.trim()
            e[Keys.COND_MAQ_ID] = cfg.condominioMaquinasId.trim()
            e[Keys.CONDOMINIO_ID] = cfg.condominioId.trim()
            e[Keys.IDENT_LOCAL] = cfg.identificadorLocal.trim()
        }
    }

    /**
     * Contrato backend POST /api/pos/authorize:
     * - Header obrigatório: x-pos-serial
     * - Body obrigatório: identificador_local, condominio_id (ou derivado do POS), valor_centavos (>0), metodo ("PIX"|"CARTAO")
     * - Body opcional: pos_serial (fallback do header), client_request_id (idempotência por tentativa)
     * valor_centavos e client_request_id são obtidos internamente (cfg.valorCentavosForAuthorize e UUID).
     */
    private fun authorize(
        cfg: PosConfig,
        metodo: String,
        onDone: (AuthorizeResult) -> Unit,
        onErr: (String, String?) -> Unit
    ) {
        val base = cfg.baseUrl.trimEnd('/')
        val url = "$base/api/pos/authorize"
        val bodyMetodo = when (metodo.trim().uppercase()) {
            "PIX" -> "PIX"
            "CARTAO" -> "CARTAO"
            else -> "CARTAO"
        }
        val valorCentavos = if (cfg.valorCentavosForAuthorize > 0) cfg.valorCentavosForAuthorize else 1600
        val clientRequestId = UUID.randomUUID().toString()
        val bodyJson = JSONObject().apply {
            put("pos_serial", cfg.posSerial)
            put("identificador_local", cfg.identificadorLocal)
            put("condominio_id", cfg.condominioId)
            put("metodo", bodyMetodo)
            put("valor_centavos", valorCentavos)
            put("client_request_id", clientRequestId)
        }.toString()

        android.util.Log.d(
            "NEXUS_AUTH",
            "REQ POST $url bodyMetodo=$bodyMetodo valor_centavos=$valorCentavos client_request_id=$clientRequestId"
        )

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("content-type", "application/json")
            .header("x-pos-serial", cfg.posSerial)
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("NEXUS_AUTH", "ERROR network", e)
                onErr("Falha de rede/servidor", "${e.javaClass.simpleName}: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()

                android.util.Log.d("NEXUS_AUTH", "RESP status=${response.code} url=${response.request.url}")

                if (!response.isSuccessful) {
                    val (title, details) = parseErrorBody(raw, response.code)
                    android.util.Log.e("NEXUS_AUTH", "ERROR status=${response.code} body=$raw")
                    onErr(title, details)
                    return
                }

                android.util.Log.d("NEXUS_AUTH", "PARSED ok pagamento_id=${JSONObject(raw).optString("pagamento_id", "")}")

                try {
                    val obj = JSONObject(raw)
                    val ok = obj.optBoolean("ok", false)

                    // Campos reais do seu backend
                    val correlationId = obj.optString("correlation_id", "").takeIf { it.isNotBlank() }

                    // pagamento_id pode vir plano ou dentro de "pagamento"
                    val pagamentoIdFlat = obj.optString("pagamento_id", "").takeIf { it.isNotBlank() }
                    val pagamentoFromObj = obj.optJSONObject("pagamento")
                        ?.optString("id", "")
                        ?.takeIf { it.isNotBlank() }
                    val pagamentoId = pagamentoIdFlat ?: pagamentoFromObj
                    val pagamentoStatus = obj.optString("pagamento_status", "").takeIf { it.isNotBlank() }

                    // Campos “genéricos” (se existirem)
                    val reason = obj.optString("reason", "").takeIf { it.isNotBlank() }
                    val message = obj.optString("message", "").takeIf { it.isNotBlank() }

                    onDone(
                        AuthorizeResult(
                            ok = ok,
                            httpCode = response.code,
                            correlationId = correlationId,
                            pagamentoId = pagamentoId,
                            pagamentoStatus = pagamentoStatus,
                            reason = reason,
                            message = message,
                            raw = raw
                        )
                    )
                } catch (_: Throwable) {
                    onDone(
                        AuthorizeResult(
                            ok = false,
                            httpCode = response.code,
                            correlationId = null,
                            pagamentoId = null,
                            pagamentoStatus = null,
                            reason = "INVALID_PAYLOAD",
                            message = "Resposta inesperada do servidor",
                            raw = raw
                        )
                    )
                }
            }
        })
    }
}

@Composable
fun PosV0FixedMachineScreen(
    readConfig: suspend () -> PosConfig,
    saveConfig: suspend (PosConfig) -> Unit,
    authorize: (PosConfig, String, (AuthorizeResult) -> Unit, (String, String?) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()

    var cfg by remember { mutableStateOf<PosConfig?>(null) }
    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }
    var showConfig by remember { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }
    var lastPagamentoId by remember { mutableStateOf<String?>(null) }

    // Estado do fluxo kiosk (um único estado de tela)
    var showDevPanel by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf(Screen.START) }
    var machines by remember { mutableStateOf<List<PosMachine>?>(null) }
    var machinesError by remember { mutableStateOf<String?>(null) }
    var selectedMachine by remember { mutableStateOf<PosMachine?>(null) }
    var selectedPriceCentavos by remember { mutableStateOf<Int?>(null) }
    var selectedPaymentMethod by remember { mutableStateOf<String?>(null) }
    var showTimeoutAviso by remember { mutableStateOf(false) }
    val api = remember(cfg?.baseUrl) { cfg?.baseUrl?.let { NexusApi(it) } }

    if (showDevPanel) {
        // --- Painel dev: UI antiga (authorize + status completo) ---
        if (showStatus && !lastPagamentoId.isNullOrBlank()) {
            PosStatusScreen(
                pagamentoId = lastPagamentoId!!,
                baseUrl = (cfg?.baseUrl ?: "https://ci.metalav.com.br"),
                onBack = {
                    showStatus = false
                    lastPagamentoId = null
                }
            )
        } else {
            devPanelContent(
                scope = scope,
                cfg = cfg,
                setCfg = { cfg = it },
                uiState = uiState,
                setUiState = { uiState = it },
                showConfig = showConfig,
                setShowConfig = { showConfig = it },
                lastPagamentoId = lastPagamentoId,
                setLastPagamentoId = { lastPagamentoId = it },
                setShowStatus = { showStatus = it },
                readConfig = readConfig,
                saveConfig = saveConfig,
                authorize = authorize,
                onOpenKiosk = { showDevPanel = false; currentScreen = Screen.START }
            )
        }
    } else {
        // --- Fluxo kiosk: roteador por currentScreen ---
        when (currentScreen) {
            Screen.START -> StartScreen(
                onStart = { currentScreen = Screen.CHOOSE_MACHINE },
                onOpenDev = { showDevPanel = true },
                onOpenConfig = { showConfig = true },
                timeoutAviso = showTimeoutAviso,
                onTimeoutAvisoDismissed = { showTimeoutAviso = false }
            )
            Screen.CHOOSE_MACHINE -> ChooseMachineScreen(
                machines = machines,
                machinesError = machinesError,
                cfg = cfg,
                api = api,
                onSelect = { machine, priceCentavos ->
                    selectedMachine = machine
                    selectedPriceCentavos = priceCentavos
                    currentScreen = Screen.CHOOSE_PAYMENT
                },
                onBack = { currentScreen = Screen.START; machines = null; machinesError = null }
            )
            Screen.CHOOSE_PAYMENT -> ChoosePaymentScreen(
                cfg = cfg,
                selectedMachine = selectedMachine,
                initialPriceCentavos = selectedPriceCentavos,
                authorize = authorize,
                scope = scope,
                onAuthorized = { id ->
                    lastPagamentoId = id
                    currentScreen = Screen.STATUS
                },
                onBack = { currentScreen = Screen.CHOOSE_MACHINE; selectedMachine = null; selectedPriceCentavos = null }
            )
            Screen.STATUS -> {
                val pid = lastPagamentoId
                val baseUrl = cfg?.baseUrl ?: "https://ci.metalav.com.br"

                KioskStatusScreen(
                    pagamentoId = pid,
                    baseUrl = baseUrl,
                    posSerial = cfg?.posSerial ?: "POS-LAB-01",
                    identificadorLocal = selectedMachine?.identificador_local ?: cfg?.identificadorLocal ?: "LAV-01",
                    onBack = {
                        currentScreen = Screen.START
                        lastPagamentoId = null
                        selectedMachine = null
                        selectedPaymentMethod = null
                    },
                    onTimeout = { showTimeoutAviso = true }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (cfg == null) cfg = readConfig()
    }

    LaunchedEffect(currentScreen, cfg) {
        if (currentScreen != Screen.CHOOSE_MACHINE) return@LaunchedEffect
        val localCfg = cfg ?: run {
            machinesError = "Configure Condomínio (UUID) e POS Serial na tela Config."
            machines = emptyList()
            return@LaunchedEffect
        }
        if (localCfg.condominioId.isBlank() || localCfg.posSerial.isBlank()) {
            machinesError = "Configure Condomínio (UUID) e POS Serial na tela Config."
            machines = emptyList()
            return@LaunchedEffect
        }
        machinesError = null
        try {
            machines = api?.getMachines(localCfg.condominioId, localCfg.posSerial) ?: emptyList()
        } catch (e: Exception) {
            machinesError = e.message ?: "Erro ao carregar máquinas"
            machines = emptyList()
        }
    }

    if (showConfig) {
        val initial = cfg ?: PosConfig(
            baseUrl = "https://ci.metalav.com.br",
            posSerial = "SAMSUNG-TESTE-001",
            condominioMaquinasId = "COLE-AQUI-O-UUID-DA-MAQUINA",
            condominioId = "",
            identificadorLocal = "LAV-01"
        )
        ConfigDialog(
            initial = initial,
            onDismiss = { showConfig = false },
            onSave = { newCfg ->
                scope.launch {
                    saveConfig(newCfg)
                    cfg = newCfg
                    showConfig = false
                }
            }
        )
    }
}

@Composable
private fun devPanelContent(
    scope: kotlinx.coroutines.CoroutineScope,
    cfg: PosConfig?,
    setCfg: (PosConfig?) -> Unit,
    uiState: UiState,
    setUiState: (UiState) -> Unit,
    showConfig: Boolean,
    setShowConfig: (Boolean) -> Unit,
    lastPagamentoId: String?,
    setLastPagamentoId: (String?) -> Unit,
    setShowStatus: (Boolean) -> Unit,
    readConfig: suspend () -> PosConfig,
    saveConfig: suspend (PosConfig) -> Unit,
    authorize: (PosConfig, String, (AuthorizeResult) -> Unit, (String, String?) -> Unit) -> Unit,
    onOpenKiosk: () -> Unit
) {
    val logs = remember { mutableStateListOf<String>() }
    fun logLine(msg: String) {
        val line = "${System.currentTimeMillis()} — $msg"
        logs.add(0, line)
        if (logs.size > 30) logs.removeAt(logs.lastIndex)
    }

    fun isUuid(s: String): Boolean {
        val v = s.trim()
        val re = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        return re.matches(v)
    }

    LaunchedEffect(Unit) {
        if (cfg == null) setCfg(readConfig())
        logLine("Config carregada (${cfg?.identificadorLocal})")
    }

    fun doAuthorize() {
        val c = cfg ?: return

        val condId = c.condominioId.trim()
        if (condId.isBlank()) {
            setUiState(UiState.Error(
                title = "Config incompleta",
                details = "Defina o Condomínio (UUID) em Config."
            ))
            logLine("Bloqueado: condominio_id vazio")
            return
        }
        if (!isUuid(condId)) {
            setUiState(UiState.Error(
                title = "Config incompleta",
                details = "Condomínio (UUID) deve ser um UUID válido."
            ))
            logLine("Bloqueado: condominio_id não é UUID")
            return
        }
        val cmid = c.condominioMaquinasId.trim()
        if (cmid.isBlank() || cmid.contains("COLE-AQUI", ignoreCase = true) || !isUuid(cmid)) {
            setUiState(UiState.Error(
                title = "Config incompleta",
                details = "Defina o CONDOMINIO_MAQUINAS_ID (UUID) em Config."
            ))
            logLine("Bloqueado: CONDOMINIO_MAQUINAS_ID inválido")
            return
        }

        setUiState(UiState.Loading)
        logLine("POST /api/pos/authorize pos=${c.posSerial} maq=${c.identificadorLocal} valor_centavos=1600")

        authorize(
            c.copy(valorCentavosForAuthorize = 1600),
            "PIX",
            { res ->
                scope.launch {
                    logLine("HTTP ${res.httpCode} ok=${res.ok} corr=${res.correlationId ?: "-"}")
                    setUiState(UiState.Done(res))
                    setLastPagamentoId(res.pagamentoId)
                }
            },
            { title, details ->
                scope.launch {
                    logLine("authorize ERROR: $title")
                    setUiState(UiState.Error(title, details))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Nexus POS (v0)", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Modo: máquina fixa (dev)", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenKiosk) { Text("Kiosk") }
                OutlinedButton(onClick = { setShowConfig(true) }) { Text("Config") }
            }
        }

        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Máquina", fontWeight = FontWeight.SemiBold)
                Text(cfg?.identificadorLocal ?: "Carregando...")
                Text("POS: ${cfg?.posSerial ?: "..."}", style = MaterialTheme.typography.bodySmall)
                Text("Base: ${cfg?.baseUrl ?: "..."}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Status", fontWeight = FontWeight.SemiBold)

                when (val s = uiState) {
                    UiState.Idle -> Text("Pronto para autorizar.")

                    UiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Autorizando…")
                    }

                    is UiState.Done -> {
                        val r = s.result

                        val color =
                            if (r.ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error

                        Text(
                            if (r.ok) "AUTORIZADO" else "NEGADO",
                            color = color,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            buildString {
                                if (!r.pagamentoStatus.isNullOrBlank()) append("pagamento_status=${r.pagamentoStatus}  ")
                                if (!r.pagamentoId.isNullOrBlank()) append("pagamento_id=${r.pagamentoId}  ")
                                if (!r.correlationId.isNullOrBlank()) append("correlation_id=${r.correlationId}  ")
                                if (!r.reason.isNullOrBlank()) append("reason=${r.reason}  ")
                                if (!r.message.isNullOrBlank()) append("msg=${r.message}")
                            }.ifBlank { "—" }
                        )

                        Spacer(Modifier.height(6.dp))
                        Text("RAW (debug):", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        SelectionContainer {
                            Text(r.raw.take(2000), style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(Modifier.height(8.dp))

                        if (r.ok && !r.pagamentoId.isNullOrBlank()) {
                            Button(
                                onClick = {
                                    setLastPagamentoId(r.pagamentoId)
                                    setShowStatus(true)
                                }
                            ) {
                                Text("Ver status do pagamento")
                            }
                        }
                    }

                    is UiState.Error -> {
                        Text("ERRO", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(s.title)
                        if (!s.details.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            SelectionContainer {
                                Text(s.details.take(2000), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { doAuthorize() },
                        enabled = cfg != null && uiState !is UiState.Loading
                    ) { Text("Autorizar") }

                    OutlinedButton(
                        onClick = { setUiState(UiState.Idle) },
                        enabled = uiState !is UiState.Loading
                    ) { Text("Limpar") }
                }
            }
        }

        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Suporte (logs)", fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Text(
                        if (logs.isEmpty()) "—" else logs.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (showConfig) {
        val initial = cfg ?: PosConfig(
            baseUrl = "https://ci.metalav.com.br",
            posSerial = "SAMSUNG-TESTE-001",
            condominioMaquinasId = "COLE-AQUI-O-UUID-DA-MAQUINA",
            condominioId = "",
            identificadorLocal = "LAV-01"
        )

        ConfigDialog(
            initial = initial,
            onDismiss = { setShowConfig(false) },
            onSave = { newCfg: PosConfig ->
                scope.launch {
                    saveConfig(newCfg)
                    setCfg(newCfg)
                    setShowConfig(false)
                    setUiState(UiState.Idle)
                    logLine("Config salva (${newCfg.identificadorLocal})")
                }
            }
        )
    }
}

@Composable
private fun StartScreen(
    onStart: () -> Unit,
    onOpenDev: () -> Unit,
    onOpenConfig: () -> Unit = {},
    timeoutAviso: Boolean = false,
    onTimeoutAvisoDismissed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Área principal: CTA "Toque para iniciar" (fluxo normal) — botão laranja como na referência
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .heightIn(min = 83.dp)
                    .widthIn(min = 322.dp)
                    .padding(horizontal = 32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MetaLavOrange),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "Toque para iniciar",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (timeoutAviso) {
                LaunchedEffect(Unit) {
                    delay(4000L)
                    onTimeoutAvisoDismissed()
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Tempo esgotado. Tente novamente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Footer fixo: logo com tamanho limitado; long-press 10s aqui abre Config
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 120.dp)
                .height(110.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            holdJob?.cancel()
                            holdJob = scope.launch {
                                delay(10_000)
                                onOpenConfig()
                            }
                            try {
                                tryAwaitRelease()
                            } finally {
                                holdJob?.cancel()
                                holdJob = null
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "Meta-Lav",
                modifier = Modifier
                    .height(90.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun ChooseMachineScreen(
    machines: List<PosMachine>?,
    machinesError: String?,
    cfg: PosConfig?,
    api: NexusApi?,
    onSelect: (PosMachine, Int?) -> Unit,
    onBack: () -> Unit
) {
    val lavadora = machines?.firstOrNull { it.tipo == "lavadora" }
    val secadora = machines?.firstOrNull { it.tipo == "secadora" }
    var lavadoraPrecoCentavos by remember { mutableStateOf<Int?>(null) }
    var secadoraPrecoCentavos by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(machines, cfg, api) {
        if (cfg == null || cfg.condominioId.isBlank() || api == null) return@LaunchedEffect
        lavadora?.let { maq ->
            try {
                lavadoraPrecoCentavos = api.fetchPrice(cfg.condominioId, maq.id, "lavadora")
            } catch (_: Exception) { lavadoraPrecoCentavos = null }
        } ?: run { lavadoraPrecoCentavos = null }
        secadora?.let { maq ->
            try {
                secadoraPrecoCentavos = api.fetchPrice(cfg.condominioId, maq.id, "secadora")
            } catch (_: Exception) { secadoraPrecoCentavos = null }
        } ?: run { secadoraPrecoCentavos = null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(onClick = onBack) {
                Text("← Voltar", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Escolha a máquina",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
        )
        if (machinesError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                machinesError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (machines == null && machinesError == null) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Carregando máquinas…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (machines != null && machines.isEmpty() && machinesError == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Nenhuma máquina disponível para este POS.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(224.dp)
                    .then(
                        if (secadora != null && secadoraPrecoCentavos != null) Modifier.clickable { onSelect(secadora, secadoraPrecoCentavos) }
                        else Modifier.alpha(0.5f)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("♨", fontSize = 36.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "SECAR",
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold
                        )
                        secadoraPrecoCentavos?.let { centavos ->
                            Text(
                                formatBrasilCentavos(centavos),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Tempo aproximado: 45 min",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(0.1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "↑",
                            fontSize = 112.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                        )
                    }
                }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(224.dp)
                    .then(
                        if (lavadora != null && lavadoraPrecoCentavos != null) Modifier.clickable { onSelect(lavadora, lavadoraPrecoCentavos) }
                        else Modifier.alpha(0.5f)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("🫧", fontSize = 36.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "LAVAR",
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold
                        )
                        lavadoraPrecoCentavos?.let { centavos ->
                            Text(
                                formatBrasilCentavos(centavos),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Tempo aproximado: 35 min",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(0.1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "↓",
                            fontSize = 112.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }
}

private fun formatBrasilCentavos(centavos: Int): String {
    return "R$ ${centavos / 100},${(centavos % 100).toString().padStart(2, '0')}"
}

@Composable
private fun ChoosePaymentScreen(
    cfg: PosConfig?,
    selectedMachine: PosMachine?,
    initialPriceCentavos: Int?,
    authorize: (PosConfig, String, (AuthorizeResult) -> Unit, (String, String?) -> Unit) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    onAuthorized: (String) -> Unit,
    onBack: () -> Unit
) {
    var isAuthorizing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var valorCentavos by remember(initialPriceCentavos) { mutableStateOf<Int?>(initialPriceCentavos) }
    var priceError by remember { mutableStateOf<String?>(null) }
    /** Mantém client_request_id estável durante uma tentativa (evita novo UUID em recompose/double-tap). */
    var attemptClientRequestId by remember { mutableStateOf<String?>(null) }
    /** Em release: após authorize 200, poll curto até confirmar PAGO e chamar execute-cycle. */
    var postAuthorizePid by remember { mutableStateOf<String?>(null) }
    var postAuthorizePhase by remember { mutableStateOf("none") } // "none" | "waiting" | "liberando" | "timeout"
    var postAuthorizeRetryKey by remember { mutableStateOf(0) }
    val api = remember(cfg?.baseUrl) { cfg?.baseUrl?.let { NexusApi(it) } }

    LaunchedEffect(initialPriceCentavos, cfg?.baseUrl, cfg?.condominioId, selectedMachine?.id, selectedMachine?.tipo) {
        if (initialPriceCentavos != null) {
            valorCentavos = initialPriceCentavos
            priceError = null
            return@LaunchedEffect
        }
        if (cfg == null || cfg.condominioId.isBlank()) {
            valorCentavos = null
            priceError = "Configure o Condomínio (UUID) na tela Config."
            return@LaunchedEffect
        }
        val machine = selectedMachine
        if (machine == null) {
            valorCentavos = null
            priceError = "Selecione uma máquina."
            return@LaunchedEffect
        }
        val apiInstance = api
        if (apiInstance == null) {
            valorCentavos = null
            priceError = "Config (baseUrl) não disponível."
            return@LaunchedEffect
        }
        priceError = null
        valorCentavos = null
        try {
            valorCentavos = kotlinx.coroutines.withTimeout(20_000L) {
                apiInstance.fetchPrice(
                    condominioId = cfg.condominioId,
                    condominioMaquinasId = machine.id,
                    serviceType = machine.tipo
                )
            }
        } catch (e: Exception) {
            priceError = when {
                e is kotlinx.coroutines.TimeoutCancellationException -> "Tempo esgotado ao carregar preço."
                else -> e.message ?: "Erro ao carregar preço"
            }
        }
    }

    LaunchedEffect(postAuthorizePid, postAuthorizePhase, postAuthorizeRetryKey, cfg, selectedMachine, api) {
        val pid = postAuthorizePid ?: return@LaunchedEffect
        if (postAuthorizePhase != "waiting") return@LaunchedEffect
        val localCfg = cfg ?: return@LaunchedEffect
        val machine = selectedMachine ?: return@LaunchedEffect
        val apiInstance = api ?: return@LaunchedEffect
        val posSerial = localCfg.posSerial
        val ident = machine.identificador_local
        val machineId = machine.id
        var lastSeenUi: String? = null
        var lastSeenPagamentoStatus: String? = null
        var errorExiting = false
        val timedOut = withTimeoutOrNull(45_000L) {
            pollLoop@ while (isActive) {
                try {
                    val resp = apiInstance.getPosStatusByIdentificadorLocal(posSerial, ident)
                    val u = resp.ui_state?.uppercase() ?: ""
                    val pStatus = resp.pagamento?.status?.uppercase() ?: ""
                    val avail = resp.availability?.uppercase() ?: ""
                    if (u != lastSeenUi || pStatus != lastSeenPagamentoStatus) {
                        android.util.Log.d("NEXUS_POLL_SHORT", "ident=$ident ui_state=$lastSeenUi→$u pagamento.status=$lastSeenPagamentoStatus→$pStatus availability=$avail")
                        lastSeenUi = u
                        lastSeenPagamentoStatus = pStatus
                    }
                    if (u == "LIVRE" || avail == "LIVRE") {
                        postAuthorizePid = null
                        postAuthorizePhase = "none"
                        onAuthorized(pid)
                        return@withTimeoutOrNull
                    }
                    val paymentConfirmed = (resp.pagamento?.status?.uppercase() == "PAGO") ||
                        (u in setOf("AGUARDANDO_LIBERACAO", "LIBERADO", "EM_USO"))
                    if (paymentConfirmed) {
                        postAuthorizePhase = "liberando"
                        try {
                            val idemKey = "pos-exec-$pid-${machineId.take(8)}"
                            apiInstance.executeCycle(idemKey, pid, machineId)
                        } catch (e: Exception) {
                            errorMsg = e.message ?: "Falha ao liberar"
                            postAuthorizePid = null
                            postAuthorizePhase = "none"
                            errorExiting = true
                            break@pollLoop
                        }
                        postAuthorizePid = null
                        postAuthorizePhase = "none"
                        onAuthorized(pid)
                        return@withTimeoutOrNull
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("NEXUS_POLL_SHORT", "ident=$ident error=${e.message}", e)
                }
                delay(2_000L)
            }
        }
        if (errorExiting) return@LaunchedEffect
        if (timedOut != null) {
            postAuthorizePhase = "timeout"
        }
    }

    /** @param uiLabel label do botão (PIX/CRÉDITO/DÉBITO) para log; @param bodyMetodo só "PIX" ou "CARTAO". */
    fun doAuthorize(uiLabel: String, bodyMetodo: String) {
        if (attemptClientRequestId != null) {
            android.util.Log.d("NEXUS_AUTH", "IGNORED double-tap/reativação uiMetodo=$uiLabel (tentativa em andamento)")
            return
        }
        errorMsg = null
        val c = cfg
        val machine = selectedMachine
        if (c == null || machine == null) {
            errorMsg = if (c == null) "Config não carregada" else "Máquina não selecionada"
        } else if (c.condominioId.isBlank()) {
            errorMsg = "Configure o Condomínio (UUID) na tela Config."
        } else {
            val centavos = valorCentavos ?: 0
            if (centavos <= 0) {
                errorMsg = "Preço não disponível"
            } else {
                attemptClientRequestId = UUID.randomUUID().toString()
                isAuthorizing = true
                val cfgWithMachine = c.copy(identificadorLocal = machine.identificador_local, valorCentavosForAuthorize = centavos)
                val apiInstance = api
                val machineId = machine.id
                authorize(cfgWithMachine, bodyMetodo, { res ->
                    scope.launch {
                        if (!res.ok || res.pagamentoId == null) {
                            attemptClientRequestId = null
                            isAuthorizing = false
                            errorMsg = res.message ?: res.reason ?: "Falha na autorização"
                            return@launch
                        }
                        val pid = res.pagamentoId
                        if (apiInstance == null) {
                            attemptClientRequestId = null
                            isAuthorizing = false
                            errorMsg = "Config não disponível"
                            return@launch
                        }
                        val useManualConfirm = BuildConfig.DEBUG
                        if (useManualConfirm) {
                            try {
                                apiInstance.confirm(pid, "stone", "pos-manual-$pid", "approved")
                                val idemKey = "pos-exec-$pid-${machineId.take(8)}"
                                apiInstance.executeCycle(idemKey, pid, machineId)
                                attemptClientRequestId = null
                                isAuthorizing = false
                                onAuthorized(pid)
                            } catch (e: Exception) {
                                attemptClientRequestId = null
                                isAuthorizing = false
                                errorMsg = e.message ?: "Falha em confirm/execute"
                            }
                        } else {
                            attemptClientRequestId = null
                            isAuthorizing = false
                            postAuthorizePid = pid
                            postAuthorizePhase = "waiting"
                        }
                    }
                }, { t, _ ->
                    scope.launch {
                        attemptClientRequestId = null
                        isAuthorizing = false
                        errorMsg = t
                    }
                })
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("Voltar", style = MaterialTheme.typography.bodyMedium)
        }
        if (postAuthorizePid != null) {
            when (postAuthorizePhase) {
                "waiting" -> {
                    Text(
                        "Aguardando pagamento / Processando confirmação",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Aguarde…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                "liberando" -> {
                    Text(
                        "Pagamento recebido! Liberando máquina…",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                "timeout" -> {
                    Text(
                        "Confirmação demorando",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            postAuthorizePhase = "waiting"
                            postAuthorizeRetryKey++
                        }
                    ) { Text("Verificar novamente") }
                }
            }
        } else if (priceError != null) {
            Text(
                priceError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (cfg != null && selectedMachine != null && priceError == null && valorCentavos == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Carregando preço…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (errorMsg != null) {
            Text(
                errorMsg!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.85f),
                verticalArrangement = Arrangement.spacedBy(43.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PaymentMethodCard(
                    icon = { PixLogoIcon() },
                    label = "PIX",
                    enabled = !isAuthorizing && postAuthorizePid == null && valorCentavos != null && priceError == null,
                    onClick = { doAuthorize("PIX", "PIX") },
                    modifier = Modifier.fillMaxWidth()
                )
                PaymentMethodCard(
                    icon = { CardIcon() },
                    label = "CRÉDITO",
                    enabled = !isAuthorizing && postAuthorizePid == null && valorCentavos != null && priceError == null,
                    onClick = { doAuthorize("CRÉDITO", "CARTAO") },
                    modifier = Modifier.fillMaxWidth()
                )
                PaymentMethodCard(
                    icon = { CardIcon() },
                    label = "DÉBITO",
                    enabled = !isAuthorizing && postAuthorizePid == null && valorCentavos != null && priceError == null,
                    onClick = { doAuthorize("DÉBITO", "CARTAO") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (isAuthorizing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Aguarde…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PixLogoIcon() {
    Image(
        painter = painterResource(R.drawable.pix),
        contentDescription = "PIX",
        modifier = Modifier.size(72.dp),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun CardIcon() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text("💳", fontSize = 40.sp)
    }
}

@Composable
private fun PaymentMethodCard(
    icon: @Composable () -> Unit,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardHeight = 134.dp
    Card(
        modifier = modifier
            .height(cardHeight)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            icon()
            Text(
                label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun PaymentCard(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    PaymentMethodCard(
        icon = { CardIcon() },
        label = label,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
private fun KioskStatusScreen(
    pagamentoId: String?,
    baseUrl: String,
    posSerial: String,
    identificadorLocal: String,
    onBack: () -> Unit,
    onTimeout: () -> Unit
) {
    var status by remember { mutableStateOf<PosStatusResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val api = remember(baseUrl) { NexusApi(baseUrl) }
    val identForLog = identificadorLocal.ifBlank { "LAV-01" }

    LaunchedEffect(posSerial, identificadorLocal, baseUrl, pagamentoId) {
        if (identificadorLocal.isBlank()) return@LaunchedEffect
        isLoading = true
        var lastUiState: String? = null
        var lastAvailability: String? = null
        val timedOut = withTimeoutOrNull(90_000L) {
            while (isActive) {
                try {
                    val resp = api.getPosStatusByIdentificadorLocal(posSerial, identificadorLocal)
                    val uState = resp.ui_state?.uppercase() ?: ""
                    val a = resp.availability?.uppercase() ?: ""
                    Log.d("NEXUS_STATUS", "PARSED ident=$identificadorLocal ui_state=$uState availability=$a ciclo.id=${resp.ciclo?.id ?: "-"} ciclo.status=${resp.ciclo?.status ?: "-"} pagamento.id=${resp.pagamento?.id ?: "null"} pagamento.status=${resp.pagamento?.status ?: "-"}")
                    if (uState != lastUiState || a != lastAvailability) {
                        Log.d("NEXUS_STATUS", "STATE_CHANGE: ui_state $lastUiState→$uState availability $lastAvailability→$a")
                        lastUiState = uState
                        lastAvailability = a
                    }
                    status = resp
                    val terminalStates = setOf("LIVRE", "FINALIZADO", "ESTORNADO", "EXPIRADO", "ERRO")
                    val isLivre = uState == "LIVRE" || a == "LIVRE"
                    if (uState in terminalStates || isLivre) {
                        Log.d("NEXUS_STATUS", "terminal or LIVRE ui_state=$uState availability=$a breaking poll")
                        break
                    }
                } catch (e: Throwable) {
                    Log.e("NEXUS_STATUS", "req ident=$identForLog error=${e.message}", e)
                }
                delay(2_500L)
            }
        }
        isLoading = false
        if (timedOut == null) {
            onTimeout()
            onBack()
        } else {
            val lastState = status?.ui_state?.uppercase() ?: ""
            val lastAvail = status?.availability?.uppercase() ?: ""
            if (lastState == "LIVRE" || lastAvail == "LIVRE") {
                Log.d("NEXUS_STATUS", "LIVRE reached, navigating back to start")
                onBack()
            }
        }
    }

    val uiState = status?.ui_state?.uppercase() ?: ""
    val availability = status?.availability?.uppercase() ?: ""
    val showIdle = uiState == "LIVRE" || availability == "LIVRE"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onBack) {
            Text("← Voltar ao menu")
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (uiState) {
                "AGUARDANDO_PAGAMENTO" -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("Processando pagamento…", style = MaterialTheme.typography.bodyMedium)
                }
                "PAGO" -> Text("Pagamento aprovado", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                "LIBERANDO" -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("Liberando máquina…", style = MaterialTheme.typography.bodyMedium)
                }
                "EM_USO" -> Text("Máquina em uso", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                "FINALIZADO" -> {
                    Text("Máquina pronta", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Pressione INICIAR na máquina para começar.", style = MaterialTheme.typography.bodyMedium)
                }
                "EXPIRADO" -> {
                    Text("Pagamento expirado", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onBack) { Text("Voltar") }
                }
                "ERRO" -> {
                    Text("Algo deu errado", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onBack) { Text("Voltar") }
                }
                "ESTORNANDO" -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("Não conseguimos iniciar a máquina", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Estorno automático em andamento…", style = MaterialTheme.typography.bodyMedium)
                }
                "ESTORNADO" -> {
                    Text("Estorno confirmado", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onBack) { Text("Voltar") }
                }
                "LIVRE" -> {
                    Text("Toque para iniciar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Selecione a máquina e escolha a forma de pagamento.", style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    if (showIdle) {
                        Text("Toque para iniciar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Selecione a máquina e escolha a forma de pagamento.", style = MaterialTheme.typography.bodyMedium)
                    } else if (isLoading && status == null) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text("Carregando…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text("Aguardando liberação…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (BuildConfig.DEBUG && status != null) {
            Text(
                "dbg: ui_state=${status?.ui_state ?: "-"} availability=${status?.availability ?: "-"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Layout da tela de pagamento com cartão (apenas visual; não altera navegação). */
@Composable
private fun CardPaymentLayout(valorCentavos: Int = 1600) {
    val valorStr = "R$ ${valorCentavos / 100},${(valorCentavos % 100).toString().padStart(2, '0')}"
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            valorStr,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(32.dp))
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("💳", fontSize = 48.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Insira ou aproxime o cartão",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

/** Layout da tela de pagamento PIX com QR (apenas visual; não altera navegação). */
@Composable
private fun PixPaymentLayout() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.pix),
            contentDescription = "PIX",
            modifier = Modifier.size(64.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(240.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("QR Code", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Escaneie o código para pagar",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun ConfigDialog(
    initial: PosConfig,
    onDismiss: () -> Unit,
    onSave: (PosConfig) -> Unit
) {
    var base by remember { mutableStateOf(initial.baseUrl) }
    var serial by remember { mutableStateOf(initial.posSerial) }
    var cmid by remember { mutableStateOf(initial.condominioMaquinasId) }
    var condId by remember { mutableStateOf(initial.condominioId) }
    var ident by remember { mutableStateOf(initial.identificadorLocal) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                onSave(
                    PosConfig(
                        baseUrl = base.trim(),
                        posSerial = serial.trim(),
                        condominioMaquinasId = cmid.trim(),
                        condominioId = condId.trim(),
                        identificadorLocal = ident.trim()
                    )
                )
            }) { Text("Salvar") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Config POS · ${BuildConfig.VERSION_NAME}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ident,
                    onValueChange = { ident = it },
                    label = { Text("Identificador local (UI)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text("BASE_URL") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = serial,
                    onValueChange = { serial = it },
                    label = { Text("POS_SERIAL") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = condId,
                    onValueChange = { condId = it },
                    label = { Text("Condomínio (UUID)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = cmid,
                    onValueChange = { cmid = it },
                    label = { Text("CONDOMINIO_MAQUINAS_ID (UUID)") }
                )
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun PreviewPosV0() {
    NexusPosTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            PosV0FixedMachineScreen(
                readConfig = {
                    PosConfig(
                        baseUrl = "https://ci.metalav.com.br",
                        posSerial = "SAMSUNG-TESTE-001",
                        condominioMaquinasId = "COLE-AQUI-O-UUID-DA-MAQUINA",
                        condominioId = "",
                        identificadorLocal = "LAV-01"
                    )
                },
                saveConfig = {},
                authorize = { _, _, onDone, _ ->
                    onDone(
                        AuthorizeResult(
                            ok = false,
                            httpCode = 200,
                            correlationId = null,
                            pagamentoId = null,
                            pagamentoStatus = null,
                            reason = "PREVIEW",
                            message = "Somente preview",
                            raw = """{"ok":false,"reason":"PREVIEW"}"""
                        )
                    )
                }
            )
        }
    }
}
