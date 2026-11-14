# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).

## [Não lançado]

### Adicionado
- Projeto Gradle com três módulos Kotlin/JVM sem dependência de Android:
  `pattern-engine`, `led-device` e `led-core`.
- Motor de padrões (R2): P1 gradiente contínuo azul → vermelho; P2 gradiente de três
  cores com ponto médio ajustável; P3 pulso (piscar ou respirar) com cor, frequência e
  faixa de atuação por item; modo de transição por sequência de cores (proposta, não
  confirmada); entrada por velocidade ou por PWM.
- Alerta crítico em vermelho que se sobrepõe a qualquer padrão e ao modo manual, ignora
  o brilho geral, não sofre defasagem nem suavização e não tem opção nos padrões que o
  desligue. Estilo fixo ou piscante.
- Limite máximo de brilho aplicado a todo quadro.
- Multi-placa (R3): até 10 placas com nome e endereço próprios, mesma configuração,
  modo sincronizado ou defasado com atraso por placa.
- Camada de dispositivo: interfaces separadas para enlace e protocolo, dois codecs
  (`sp63x`, `sp61x-pwm`), fila de comandos, envio só do quadro mais recente, reconexão
  com recuo exponencial, tempos limite, e placa simulada com injeção de falhas.
- Controlador: telemetria → padrões → placas, suavização da entrada, comportamento sem
  telemetria, modo manual com as funções da própria placa.
- Persistência das configurações em JSON versionado, com recuperação de arquivo corrompido.
- 166 testes unitários.
- Documentação: questões em aberto, spec do app de referência (incompleta), mapeamento
  de cores, protocolo, integração com o WheelLog, arquitetura, checklist de campo e
  relatório de testes.

### Pendente
- App Android: fork do WheelLog, ponte de telemetria, enlace BLE e telas.
- Levantamento completo do app de referência (telas, botões, valores padrão).
- Validação de protocolo, temporização e brilho em hardware.
