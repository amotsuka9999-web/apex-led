# Publicar na Play Store

Na Play Store, o app do relógio instala sozinho quando a pessoa instala o do celular, e as atualizações chegam automaticamente.

## 1. Criar a chave de publicação (uma vez, guarde para sempre)

No computador, com Java instalado:

```
keytool -genkeypair -v -keystore apex-release.jks -alias apexled -keyalg RSA -keysize 4096 -validity 10000
```

Guarde o arquivo `apex-release.jks` e as senhas em dois lugares seguros. **Sem essa chave não dá para atualizar o app na loja.**
Nunca coloque essa chave no repositório.

## 2. Colocar a chave no GitHub (secrets)

GitHub → repositório → Settings → Secrets and variables → Actions → New repository secret:

| Nome | Valor |
|---|---|
| `APEX_KEYSTORE_B64` | o arquivo em base64: `base64 -w0 apex-release.jks` (no Mac: `base64 -i apex-release.jks`) |
| `APEX_KEYSTORE_PASSWORD` | senha do keystore |
| `APEX_KEY_ALIAS` | `apexled` |
| `APEX_KEY_PASSWORD` | senha da chave |

A partir daí, cada build gera o artefato **apex-led-play-store** com `apex-led-celular.aab` e `apex-led-relogio.aab`.

## 3. Play Console

1. Crie a conta de desenvolvedor em https://play.google.com/console (taxa única de US$ 25; conta pessoal nova exige um teste fechado com 12 testadores por 14 dias antes da produção).
2. Crie o app "Apex LED" e responda os questionários: classificação etária, público-alvo, segurança dos dados (o app não coleta dados; usa Bluetooth para falar com a roda e o LED; os passeios ficam só no celular).
3. Política de privacidade: uma página simples dizendo isso (pode ser uma página do GitHub).
4. Em **Configuração avançada → Formatos**, adicione **Wear OS**.
5. Envie `apex-led-celular.aab` na faixa de teste e `apex-led-relogio.aab` na faixa de teste do Wear OS.
6. Capturas de tela: celular (aba Início e Segurança) e relógio (páginas Modo seguro e Setas).

## Observações

- O `applicationId` é `app.apexled.monociclo` nos dois apps; não troque depois de publicar.
- O APK de teste do link fixo continua usando a chave de debug do repositório. Instalar a versão da loja por cima exige desinstalar a de teste antes.
- A Play pede `targetSdk` recente todo ano; ajuste em `app/build.gradle.kts` e `wear/build.gradle.kts`.
