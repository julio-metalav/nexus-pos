package br.com.metalav.nexuspos

data class PosStatusResponse(
    val ok: Boolean,
    val pagamento: Pagamento?,
    val ciclo: Ciclo?,
    val iot_command: IotCommand?,
    val ui_state: String?,
    val availability: String? = null,
    val error: String? = null
)

data class Pagamento(
    val id: String,
    val status: String?,
    val valor_centavos: Int?,
    val metodo: String?,
    val created_at: String?,
    val expires_at: String?
)

data class Ciclo(
    val id: String,
    val status: String?,
    val condominio_maquinas_id: String?,
    val condominio_id: String?
)

data class IotCommand(
    val id: String,
    val cmd_id: String?,
    val status: String?,
    val created_at: String?,
    val expires_at: String?,
    val correlation_id: Any?
)

