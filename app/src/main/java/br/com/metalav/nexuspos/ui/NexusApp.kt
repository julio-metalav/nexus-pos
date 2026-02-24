package br.com.metalav.nexuspos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NexusApp() {
    // Stub mínimo só pra compilar e validar que o conflito acabou.
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Nexus POS",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Selecione uma máquina para iniciar a venda",
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Iniciar Venda")
            }
        }
    }
}
