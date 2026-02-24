# Auditoria + Patch Nexus POS – Contratos e Entregáveis

## 1) AUDITORIA DE CONTRATOS (backend – fonte da verdade)

### GET /api/pos/machines
- **Query:** `condominio_id` (obrigatório).
- **Headers:** `x-pos-serial` (obrigatório).
- **Resposta 200:** `{ ok: true, items: [ { id, identificador_local, tipo, ativa, pos_device_id, condominio_id } ] }`.
- **Erros:** 400 missing_condominio_id | missing_pos_serial; 401 pos_not_found; 403 pos_condominio_mismatch; 500 db_error | internal_error. Formato: `error_v1: { code, message }`, legado `error` (string).

### POST /api/payments/price
- **Body (parsePriceInput):** `condominio_id`, `condominio_maquinas_id`, `service_type` ("lavadora"|"secadora"), `channel` (ex.: "pos"), `origin` (ex.: { pos_device_id, user_id }), `context: { coupon_code }`.
- **Resposta 200:** `{ ok: true, quote: { quote_id, amount (BRL), currency, valid_until, pricing_hash, rule_id } }`.
- **Erros:** 400 por parse; 404 machine_not_found | price_not_found; 500. `quote.amount` em BRL; centavos = round(amount * 100).

### POST /api/pos/authorize
- **Headers:** `x-pos-serial` (obrigatório; fallback body pos_serial).
- **Body obrigatório:** `identificador_local`, `condominio_id` (opcional se POS tem condominio_id), **`valor_centavos`** (número > 0), **`metodo`** ("PIX" | "CARTAO").
- **Resposta 200:** `{ ok, reused?, correlation_id, pagamento_id, pagamento_status }`.
- **Erros:** 400 missing_identificador_local | missing_condominio_id | missing_valor_centavos | invalid_valor_centavos | missing_metodo (metodo inválido); 401 pos_not_found; 403 pos_condominio_mismatch | canary_not_allowed | machine_not_bound_to_pos; 404 machine_not_found; 409 duplicate_machine_identifier | missing_gateway_id. Sempre `error_v1: { code, message }`.

### POST /api/payments/confirm
- **Body (parseConfirmInput):** `payment_id`, `provider` ("stone"|"asaas"), `provider_ref`, `result` ("approved"|"failed"), channel/origin.
- **Resposta 200:** `{ ok, replay?, correlation_id, payment_id, status }`.
- **Erros:** 400 por parse; 404 payment_not_found; 500.

### POST /api/payments/execute-cycle
- **Body (parseExecuteCycleInput):** `idempotency_key`, `payment_id`, `condominio_maquinas_id`, channel/origin.
- **Resposta 200:** `{ ok, replay?, correlation_id, cycle_id, command_id, status }`.
- **Erros:** 400 por parse; 404 payment_not_found; 409 payment_not_confirmed | cycle_expired; 500.

### GET /api/pos/status
- **Query:** `pagamento_id` OU `identificador_local` (com header `x-pos-serial` obrigatório).
- **Resposta 200:** `{ ok, availability (LIVRE|EM_USO), machine, pagamento, ciclo, iot_command, ui_state }`.
- **Erros:** 400 missing_param | missing_pos_serial; 401 pos_not_found; 404 payment_not_found | machine_not_found; 500.

---

## 2) O QUE FOI CORRIGIDO NO APP

- **HTTP 400 no authorize:** o backend exige `valor_centavos` e `metodo` ("PIX"|"CARTAO"). O app não enviava `valor_centavos`. Passou a enviar `valor_centavos` (valor do price) em todo authorize e a assinatura do authorize inclui `Int` (centavos). Método já era "PIX" ou "CARTAO" nos botões.
- **Payload price:** já estava alinhado (channel, origin, context). Cálculo de centavos passou a usar `roundToInt()` para evitar erro de float.
- **UI de erro:** em falha de authorize (e qualquer onErr que use a resposta), o app passa a exibir `error_v1.code + error_v1.message` (ou fallback `error`/`message`/`text`), com fallback final "HTTP &lt;code&gt;". Função `parseErrorBody(raw, httpCode)` em MainActivity.
- **Observabilidade:** logs com tags fixas:
  - **NEXUS_MACHINES:** REQ GET url + condominio; RESP status + url; ERROR body; PARSED count + ids + tipos.
  - **NEXUS_PRICE:** (já existia) REQ/RESP/PARSED/ERROR.
  - **NEXUS_AUTH:** REQ POST + condominio, ident, metodo, valor_centavos; RESP status; ERROR body; PARSED ok + pagamento_id.
  - **NEXUS_STATUS:** REQ GET + url (e ident quando por identificador_local); RESP status; PARSED ui_state, availability, pagamento_id, ciclo_id; ERROR/PARSE_ERROR.
  - **NEXUS_POLL_SHORT:** a cada mudança de `ui_state` no poll pós-authorize (release): `ident=... ui_state=old→new`.
  - **NEXUS_CONFIRM / NEXUS_EXEC:** REQ/RESP/ERROR nos métodos `NexusApi.confirm()` e `NexusApi.executeCycle()` (prontos para quando o fluxo chamar confirm/execute-cycle).
  - **NEXUS_REVALIDATE:** (quando existir endpoint revalidate) REQ/RESP/ERROR.
- **Dev panel:** authorize no painel dev passou a enviar 1600 centavos (default debug) para não quebrar o fluxo fixo.

---

## 3) BUILD E INSTALAÇÃO

```bat
.\gradlew.bat :app:installDebug
```

(Se quiser só compilar: `.\gradlew.bat :app:assembleDebug`.)

---

## 4) VALIDAÇÃO DE LOGS (adb)

Filtrar por tags do Nexus (substituir `<PID>` pelo processo do app, ou omitir `--pid` para ver todos):

```bat
adb logcat --pid=<PID> | findstr /i "NEXUS_MACHINES NEXUS_PRICE NEXUS_AUTH NEXUS_POLL_SHORT NEXUS_CONFIRM NEXUS_EXEC NEXUS_STATUS NEXUS_REVALIDATE"
```

Ou sem PID:

```bat
adb logcat | findstr /i "NEXUS_MACHINES NEXUS_PRICE NEXUS_AUTH NEXUS_POLL_SHORT NEXUS_CONFIRM NEXUS_EXEC NEXUS_STATUS NEXUS_REVALIDATE"
```

---

## 5) FLUXO APÓS AUTHORIZE (referência)

- Backend retorna `pagamento_id` e `pagamento_status` (ex.: "CRIADO").
- **PIX:** a UI pode exibir QR/payload se o backend devolver no authorize ou em endpoint separado (não alterado neste patch).
- **Confirm:** normalmente chamado pelo provider (webhook). O app chama `NexusApi.confirm()` apenas em **build de debug** (ver regra abaixo).
- **Execute-cycle:** chamado quando o pagamento está confirmado (PAGO); o app chama após confirm apenas em debug.
- **Status:** polling por `pagamento_id` ou por `identificador_local` + header `x-pos-serial`; cancelar ao sair da tela (LaunchedEffect + isActive/cancel).

**Regra — Confirm manual é DEV-only:**  
O fluxo authorize→confirm→execute-cycle com **confirm manual** (pos-manual-$pid, approved) roda somente quando `BuildConfig.DEBUG == true` (build debug/CI).

**Release — Poll curto pós-authorize (produção robusta):**  
Em **release**, após authorize 200 o app **não** navega imediatamente para a tela de status. Em vez disso:

1. Mostra na própria tela de pagamento: "Aguardando pagamento / Processando confirmação" (com indicador de progresso).
2. Faz **poll curto** a cada 2 s por até 45 s: `GET /api/pos/status?identificador_local=...` (header `x-pos-serial`).
3. Quando `ui_state` indicar pagamento confirmado / pronto para liberar (ex.: **PAGO**, ou já LIBERANDO/EM_USO/FINALIZADO), o app chama `executeCycle(idempotency_key, payment_id, machine_id)` e **só então** navega para a tela STATUS.
4. Enquanto aguarda: estado "Liberando máquina…" ao detectar confirmação; se estourar o tempo (45 s), mostra "Confirmação demorando" e botão **"Verificar novamente"**, que reinicia o poll (sem novo authorize).
5. Log **NEXUS_POLL_SHORT** a cada mudança de `ui_state` no poll (`ident=... ui_state=old→new`). Mantidos NEXUS_AUTH, NEXUS_EXEC, NEXUS_STATUS.

**Revalidate (opcional):**  
Um endpoint `POST /api/payments/revalidate` pode ser implementado no backend (DEV/PROD safe): valida tenant + POS whitelist, consulta o provedor (Stone/Asaas) pelo `payment_id`/`provider_ref` e, se pago, marca o pagamento como PAGO e retorna ok. **Não** executa ciclo automaticamente; o POS chama execute-cycle depois para manter o fluxo claro. Logs **NEXUS_REVALIDATE** (REQ/RESP/ERROR). O app pode chamar revalidate antes ou no "Verificar novamente" se o endpoint existir.

---

## 6) RESULTADO — Auditoria pós-patch (Authorize/Confirm/Execute/Enums)

### Dúvidas confirmadas / ajustes feitos

| Dúvida | Situação | Ajuste |
|--------|----------|--------|
| **A) Enum método** | Backend aceita só `"PIX"` ou `"CARTAO"`. App já enviava isso nos botões; CRÉDITO/DÉBITO já mapeavam para `CARTAO`. | Garantido: `doAuthorize(uiLabel, bodyMetodo)` com botões `("PIX","PIX")`, `("CRÉDITO","CARTAO")`, `("DÉBITO","CARTAO")`. No `authorize()` o body usa `bodyMetodo` normalizado (qualquer outro valor vira `CARTAO`). Log NEXUS_AUTH inclui `uiMetodo=... bodyMetodo=PIX|CARTAO`. |
| **B) Contrato authorize** | Backend: header `x-pos-serial`; body `identificador_local`, `condominio_id`, `valor_centavos`, `metodo` ("PIX"\|"CARTAO"). | Contrato documentado em comentário no `authorize()`. Request do app já era espelho; incluído `client_request_id` opcional. |
| **C) client_request_id** | Backend usa quando enviado (idempotência por tentativa). | App gera `UUID.randomUUID()` por clique em PIX/CRÉDITO/DÉBITO e envia no body. Log NEXUS_AUTH mostra `client_request_id=...` ou `null`. |
| **D) Confirm / Execute-cycle** | Endpoints: POST `/api/payments/confirm` e POST `/api/payments/execute-cycle`. Bodies alinhados a `parseConfirmInput` e `parseExecuteCycleInput`. | NexusApi.confirm() e executeCycle() já chamam os paths corretos. Em falha, exceção lançada com mensagem no formato `[code] message` (error_v1). Qualquer UI que chame e faça catch deve exibir `e.message`. Logs NEXUS_CONFIRM e NEXUS_EXEC com REQ/RESP/ERROR e body bruto. |
| **E) Status polling** | Evitar spam e deixar transições visíveis. | Log `NEXUS_STATUS STATE_CHANGE: ui_state old→new availability old→new` apenas quando `ui_state` ou `availability` mudam. Na tela de status do kiosk, linha de debug: `dbg: ui_state=... availability=...`. |

### O que era bug e como foi corrigido

- Nenhum bug novo encontrado nesta auditoria. O 400 do authorize já tinha sido corrigido (valor_centavos). Esta rodada garantiu: (1) método sempre PIX ou CARTAO e logado; (2) client_request_id enviado por tentativa; (3) erros de confirm/execute com [code] message; (4) status com log de mudança de estado e debug na UI.

### Checklist de teste manual

1. [ ] **Config:** Condomínio (UUID), POS Serial e (se dev) CONDOMINIO_MAQUINAS_ID preenchidos.
2. [ ] **Machines:** Abrir fluxo kiosk → escolher máquina → lista carrega (GET /api/pos/machines). Log: `NEXUS_MACHINES REQ/RESP/PARSED`.
3. [ ] **Price:** Na tela de pagamento, preço aparece (ex.: R$ 16,00). Log: `NEXUS_PRICE REQ/RESP/PARSED amount=... centavos=...`.
4. [ ] **Authorize PIX:** Clicar PIX → authorize 200. Log: `NEXUS_AUTH REQ ... uiMetodo=PIX bodyMetodo=PIX valor_centavos=... client_request_id=...` e `RESP status=200`, `PARSED ok pagamento_id=...`.
5. [ ] **Authorize cartão:** Clicar CRÉDITO ou DÉBITO → authorize 200. Log: `uiMetodo=CRÉDITO bodyMetodo=CARTAO` (ou DÉBITO/CARTAO).
6. [ ] **Erro na UI:** Se authorize falhar (ex.: 400), a tela deve mostrar `[code] message` (ex.: `[missing_valor_centavos] valor_centavos é obrigatório.`), não só "HTTP 400".
7. [ ] **Confirm/Execute:** Se o fluxo chamar confirm ou execute-cycle e falhar, log com ERROR + body; exceção com `[code] message`.
8. [ ] **Status:** Após autorizar, ir para tela de status (por identificador_local). Log: `NEXUS_STATUS STATE_CHANGE: ...` quando estado mudar. Na tela: linha `dbg: ui_state=... availability=...`.
9. [ ] **Até LIVRE:** Ciclo completar (ou simular) e estado voltar a LIVRE; app mostra "Máquina liberada" e não fica preso em "Aguardando liberação".

### Exemplos de logs esperados

```
NEXUS_AUTH  REQ POST .../api/pos/authorize uiMetodo=PIX bodyMetodo=PIX valor_centavos=1600 client_request_id=a1b2c3d4-...
NEXUS_AUTH  RESP status=200 url=...
NEXUS_AUTH  PARSED ok pagamento_id=...
```

```
NEXUS_AUTH  REQ POST ... uiMetodo=CRÉDITO bodyMetodo=CARTAO valor_centavos=1600 client_request_id=...
```

```
NEXUS_CONFIRM  REQ POST .../api/payments/confirm payment_id=... provider=stone result=approved
NEXUS_CONFIRM  RESP status=200 body=...
```

```
NEXUS_EXEC  REQ POST .../api/payments/execute-cycle payment_id=... maquina=...
NEXUS_EXEC  RESP status=200 body=...
```

```
NEXUS_STATUS  STATE_CHANGE: ui_state null→AGUARDANDO_PAGAMENTO availability null→EM_USO
NEXUS_STATUS  STATE_CHANGE: ui_state AGUARDANDO_PAGAMENTO→LIVRE availability EM_USO→LIVRE
```

---

## 7) ROBUSTEZ FINAL (real-world)

### 1) Persistência do client_request_id durante a tentativa

- **Risco:** UUID gerado dentro de `doAuthorize` a cada clique; em recompose/reativação ou double-tap poderia gerar novo UUID para a “mesma” tentativa.
- **Ajuste:** Estado `attemptClientRequestId` (remember) na tela de pagamento:
  - Ao iniciar uma tentativa: `attemptClientRequestId = UUID.randomUUID().toString()` e `isAuthorizing = true`.
  - O mesmo `attemptClientRequestId` é enviado no authorize e mantido até o fim da tentativa.
  - Nos callbacks (sucesso ou erro): `attemptClientRequestId = null` e `isAuthorizing = false`.
  - Se `doAuthorize` for chamado com `attemptClientRequestId != null` (ex.: double-tap antes do recompose): **return** imediato e log `NEXUS_AUTH IGNORED double-tap/reativação ...`.
- Assim o ID fica estável durante toda a tentativa e não é regenerado até terminar (sucesso/erro).

### 2) Fonte da verdade do condominio_id (serial vs body)

- **Backend (app/api/pos/authorize/route.ts):**
  - `condominio_id` no body **não é obrigatório**.
  - Se omitido ou vazio, o backend usa `posDevice.condominio_id` (POS já foi resolvido pelo header `x-pos-serial`).
  - Se enviado no body, o backend **valida**: `posDevice.condominio_id === condominio_id`; se diferente, responde 403 `pos_condominio_mismatch`.
- **Fonte da verdade:** o condomínio canônico é o do **POS** (`pos_devices.condominio_id`). O body serve para (a) evitar dependência do POS ter condominio_id em dev ou (b) validar que o cliente está pedindo o mesmo condomínio do POS.
- **App:** continua enviando `condominio_id` da config (igual ao usado em machines/price). Se a config estiver errada (condomínio diferente do POS), o backend devolve 403 com mensagem clara. Nenhuma alteração obrigatória no app; manter o envio garante consistência com o resto do fluxo (machines, price).

### 3) Guardrails contra double-tap e chamada fora de ordem

- **Double-tap / tentativa em andamento:**
  - Botões PIX/CRÉDITO/DÉBITO: `enabled = !isAuthorizing && valorCentavos != null && priceError == null` → desabilitados enquanto authorize está em andamento.
  - No início de `doAuthorize`: se `attemptClientRequestId != null`, **return** e log `IGNORED double-tap/reativação` → segundo clique (ou reentrada) é ignorado mesmo antes do recompose.
- **Confirm / Execute-cycle:**
  - O fluxo atual do app **não** chama confirm nem execute-cycle automaticamente (são métodos em NexusApi para uso futuro ou manual).
  - **Regra para quando integrar:** chamar execute-cycle **somente após** confirm retornar OK (pagamento PAGO). Manter flags por etapa (ex.: `isConfirming`, `isExecuting`) e desabilitar ações até a etapa terminar; logs NEXUS_CONFIRM e NEXUS_EXEC já existem.

### Exemplo de log quando double-tap é ignorado

Usuário clica PIX duas vezes em sequência rápida:

```
NEXUS_AUTH  REQ POST .../api/pos/authorize uiMetodo=PIX bodyMetodo=PIX valor_centavos=1600 client_request_id=a1b2c3d4-e5f6-...
NEXUS_AUTH  IGNORED double-tap/reativação uiMetodo=PIX (tentativa em andamento)
NEXUS_AUTH  RESP status=200 url=...
NEXUS_AUTH  PARSED ok pagamento_id=...
```

O segundo clique não gera nova requisição; o primeiro request segue com um único `client_request_id`.
