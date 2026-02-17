package br.com.metalav.nexuspos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.com.metalav.nexuspos.ui.theme.NexusPosTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.IOException

// DataStore
private val ComponentActivity.dataStore by preferencesDataStore(name = "pos_config")

private object Keys {
    val BASE_URL = stringPreferencesKey("base_url")
    val POS_SERIAL = stringPreferencesKey("pos_serial")
    val COND_MAQ_ID = stringPreferencesKey("condominio_maquinas_id")
    val IDENT_LOCAL = stringPreferencesKey("identificador_local")
}

data class PosConfig(
    val baseUrl: String,
    val posSerial: String,
    val condominioMaquinasId: String,
    val identificadorLocal: String
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
                        authorize = { cfg, onDone, onErr -> authorize(cfg, onDone, onErr) }
                    )
                }
            }
        }
    }

    private suspend fun readConfig(): PosConfig {
        val prefs = dataStore.data.first()
        val base = prefs[Keys.BASE_URL] ?: "https://ci.metalav.com.br"
        val serial = prefs[Keys.POS_SERIAL] ?: "SAMSUNG-TESTE-001"
        val cmid = prefs[Keys.COND_MAQ_ID] ?: "COLE-AQUI-O-UUID-DA-MAQUINA"
        val ident = prefs[Keys.IDENT_LOCAL] ?: "LAV-01"
        return PosConfig(
            baseUrl = base,
            posSerial = serial,
            condominioMaquinasId = cmid,
            identificadorLocal = ident
        )
    }

    private suspend fun saveConfig(cfg: PosConfig) {
        dataStore.edit { e ->
            e[Keys.BASE_URL] = cfg.baseUrl.trim()
            e[Keys.POS_SERIAL] = cfg.posSerial.trim()
            e[Keys.COND_MAQ_ID] = cfg.condominioMaquinasId.trim()
            e[Keys.IDENT_LOCAL] = cfg.identificadorLocal.trim()
        }
    }

    private fun authorize(
        cfg: PosConfig,
        onDone: (AuthorizeResult) -> Unit,
        onErr: (String, String?) -> Unit
    ) {
        val base = cfg.baseUrl.trimEnd('/')
        val url = "$base/api/pos/authorize"

        val bodyJson = JSONObject()
            .put("pos_serial", cfg.posSerial)
            .put("identificador_local", cfg.identificadorLocal)
            .put("metodo", "PIX")
            .put("valor_centavos", 1600)
            .toString()

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("content-type", "application/json")
            // Segurança extra: backend aceita em body OU header
            .header("x-pos-serial", cfg.posSerial)
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onErr("Falha de rede/servidor", "${e.javaClass.simpleName}: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    onErr("HTTP ${response.code}", raw.take(2000))
                    return
                }

                try {
                    val obj = JSONObject(raw)
                    val ok = obj.optBoolean("ok", false)

                    // Campos reais do seu backend
                    val correlationId = obj.optString("correlation_id", null).takeIf { !it.isNullOrBlank() }
                    val pagamentoId = obj.optString("pagamento_id", null).takeIf { !it.isNullOrBlank() }
                    val pagamentoStatus = obj.optString("pagamento_status", null).takeIf { !it.isNullOrBlank() }

                    // Campos “genéricos” (se existirem)
                    val reason = obj.optString("reason", null).takeIf { !it.isNullOrBlank() }
                    val message = obj.optString("message", null).takeIf { !it.isNullOrBlank() }

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
    authorize: (PosConfig, (AuthorizeResult) -> Unit, (String, String?) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()

    var cfg by remember { mutableStateOf<PosConfig?>(null) }
    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }
    var showConfig by remember { mutableStateOf(false) }

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
        cfg = readConfig()
        logLine("Config carregada (${cfg?.identificadorLocal})")
    }

    fun doAuthorize() {
        val c = cfg ?: return

        val cmid = c.condominioMaquinasId.trim()
        if (cmid.isBlank() || cmid.contains("COLE-AQUI", ignoreCase = true) || !isUuid(cmid)) {
            uiState = UiState.Error(
                title = "Config incompleta",
                details = "Defina o CONDOMINIO_MAQUINAS_ID (UUID) em Config."
            )
            logLine("Bloqueado: CONDOMINIO_MAQUINAS_ID inválido")
            return
        }

        uiState = UiState.Loading
        logLine("POST /api/pos/authorize pos=${c.posSerial} maq=${c.identificadorLocal}")

        authorize(
            c,
            { res ->
                scope.launch {
                    logLine("HTTP ${res.httpCode} ok=${res.ok} corr=${res.correlationId ?: "-"}")
                    uiState = UiState.Done(res)
                }
            },
            { title, details ->
                scope.launch {
                    logLine("authorize ERROR: $title")
                    uiState = UiState.Error(title, details)
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
                Text("Modo: máquina fixa", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = { showConfig = true }) { Text("Config") }
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
                        Text(
                            if (r.ok) "AUTORIZADO" else "NEGADO",
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
                        onClick = { uiState = UiState.Idle },
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
                    uiState = UiState.Idle
                    logLine("Config salva (${newCfg.identificadorLocal})")
                }
            }
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
                        identificadorLocal = ident.trim()
                    )
                )
            }) { Text("Salvar") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Config POS") },
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
                        identificadorLocal = "LAV-01"
                    )
                },
                saveConfig = {},
                authorize = { _, onDone, _ ->
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
