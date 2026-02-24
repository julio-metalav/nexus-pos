package br.com.metalav.nexuspos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.metalav.nexuspos.NexusApi
import br.com.metalav.nexuspos.PosStatusResponse
import kotlinx.coroutines.launch

@Composable
fun PosStatusScreen(
    pagamentoId: String,
    baseUrl: String,
    onBack: () -> Unit
) {
    var status by remember { mutableStateOf<PosStatusResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember(baseUrl) { NexusApi(baseUrl) }

    LaunchedEffect(pagamentoId) {
        if (pagamentoId.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        try {
            status = api.getPosStatus(pagamentoId)
        } catch (e: Throwable) {
            error = e.message ?: "Erro ao buscar status"
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("← Voltar")
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        } else {
            val s = status
            if (s != null) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Status do pagamento", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("ok: ${s.ok}")
                    s.pagamento?.let { p ->
                        Text("pagamento.id: ${p.id}, status: ${p.status}")
                    }
                    s.iot_command?.let { i ->
                        Text("iot_command.status: ${i.status}")
                    }
                    s.ciclo?.let { c ->
                        Text("ciclo.status: ${c.status}")
                    }
                    s.error?.let { Text("error: $it", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
