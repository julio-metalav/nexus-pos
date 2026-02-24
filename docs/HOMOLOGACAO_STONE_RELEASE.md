\# Nexus POS – Controle de Assinatura Release (Stone)



\## Status Atual



Data: 19/02/2026  

Projeto: Nexus POS  

ApplicationId: br.com.nexuspayments.pos  



\## APK Release



Arquivo:

app/build/outputs/apk/release/app-release.apk



Build: SUCCESSFUL  

Gradle task: :app:assembleRelease  



\## Assinatura Release



Keystore:

nexuspos-release.jks



Alias:

nexuspos



Validade do certificado:

Até 07/07/2053



Fingerprints:



SHA1:

C2:FA:2D:53:90:B9:10:A0:7F:57:BE:CC:D6:7F:4B:28:CE:24:6C:96



SHA-256:

78:19:FF:DF:7A:1C:76:EB:CE:F1:F8:C9:D1:66:2C:60:6B:46:85:B1:E3:ED:F4:78:EC:7A:09:D7:D9:F8:F7:E5



\## Observações Importantes



\- Esta keystore é definitiva.

\- NÃO perder o arquivo nexuspos-release.jks.

\- NÃO alterar alias.

\- NÃO alterar applicationId.

\- Todas as futuras versões devem ser assinadas com esta mesma keystore.

\- Usar sempre build Release para homologação Stone.



\## Segurança



Arquivos protegidos por .gitignore:

\- \*.jks

\- keystore.properties



Nunca versionar credenciais.



