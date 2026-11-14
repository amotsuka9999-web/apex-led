# Questões em aberto

Nenhuma destas perguntas foi respondida por suposição. Para cada uma está indicado o que
ela bloqueia e como o código se comporta enquanto não há resposta (sempre um valor
provisório, configurável e fácil de trocar).

## Situação após a especificação revisada (2026-11-14)

A especificação revisada respondeu parte das perguntas. O que vale agora:

| Pergunta | Situação |
|---|---|
| Q1 modelo da placa | **Respondida: SP621E.** Codec `sp621e` adicionado e posto como padrão. Versão do Balanx analisada continua desconhecida. |
| Q2 roda e firmware | **Aberta** (dúvida 1 da especificação): "Nosfet" pode ser a roda ou a linha da placa. |
| Q3 o que é crítico | **Respondida: PWM ≥ 96 %.** É o padrão de `SafetyConfig`. Os outros limiares continuam disponíveis e desligados. |
| Q4 modo de transição | **Substituída** pelos Modos 1, 2 e 3. Os padrões do rascunho anterior (gradiente contínuo, três cores, pulso, sequência) continuam no motor, fora da tela. Decidir se devem ser removidos. |
| Q5 iOS | **Respondida: só Android.** |
| Q6 URL do WheelLog | **Aberta** (dúvida 4). |
| Q7 Balanx / BanlanX / Nosfet | **Aberta.** A SP621E é uma controladora da linha SP LED usada com o app BanlanX, o que reforça a hipótese, mas a relação com a Nosfet não foi confirmada. |
| Q8 vermelho crítico x vermelho do padrão | **Encaminhada:** o alerta agora é, por padrão, vermelho piscando a 2 Hz (entre o limite de brilho e 25 % dele), distinto do vermelho fixo do Modo 1; a opção "vermelho fixo" continua configurável. Falta a confirmação (dúvida 3). |
| Q13 limites | O Modo 3 segue a decisão de projeto: limite padrão de 5 Hz, configurável de 0,5 a 10 Hz, com aviso na tela. |

Respostas do dono do projeto (2026-11-14, depois da especificação revisada):

- **Q7 respondida em parte:** o app de referência é o **BanlanX**. A placa que hoje é controlada
  pelo BanlanX é a que este app vai controlar.
- **Q2 respondida em parte:** a roda do projeto é da **Nosfet**; os testes iniciais serão feitos
  com um **Begode A2**, que está em mãos. No WheelLog as rodas Begode usam o `GotwayAdapter`
  (as Nosfet usam o `VeteranAdapter`), então o PWM vem por outro caminho em cada uma: o que for
  validado no A2 precisa ser conferido de novo na Nosfet. Falta saber o modelo da Nosfet e os
  firmwares.

Dúvidas novas desta rodada:

- **Q16. Primeiro ponto do Modo 1 é azul?** Implementado como azul (dúvida 2 da especificação).
- **Q17. SP621E é uma controladora de fita endereçável (SPI), não PWM.** Em fita COB endereçável o
  comando de cor sólida pinta a fita inteira, que é o que os padrões usam. Se a fita for COB RGB
  comum (4 fios), o modelo da placa precisa ser conferido.
- **Q18. Modo 3 e mudança de velocidade.** A frequência é proporcional à entrada e a fase é
  calculada do relógio; quando a velocidade muda, a alternância pode dar um salto de fase
  (uma troca fora de ritmo). Corrigir exige acumular fase no controlador. Avaliar em campo.
- **Q19. Interpolação.** Modos 1 e 2 interpolam em RGB linear por canal (decisão da Fase 3).
  O trecho azul→verde passa por um azul-esverdeado mais escuro; se incomodar, trocar para HSV.
- **Q20. O limiar de 96 % pode ser desligado na tela de alerta** (não na de padrões). Confirmar se
  deve ser fixo.

O texto abaixo é o registro original, anterior à especificação revisada.

## Perguntas do pedido original

### Q1. Modelo exato da placa LED e versão do Balanx analisada
- **Bloqueia:** escolha do protocolo; lista de funções e valores padrão da paridade (R1);
  validação em hardware.
- **Enquanto isso:** a camada de dispositivo aceita mais de um protocolo. Há dois
  candidatos implementados a partir de documentação pública (`sp63x` e `sp61x-pwm`,
  ver [protocolo-led.md](protocolo-led.md)). Nenhum foi testado em placa real.
- **O que ajuda a responder:** foto da etiqueta da placa (o modelo costuma ser `SP6xxE`),
  o nome com que ela aparece na busca Bluetooth e a versão do app em *Configurações → Sobre*.

### Q2. Marca e firmware da roda (fonte e unidade do PWM)
- **Bloqueia:** confirmação de que o PWM lido é o real da controladora e não o estimado.
- **O que já se sabe (do código do WheelLog):** o Nosfet Apex é tratado pelo adaptador
  Veteran (`mVer == 42`). Nesse adaptador o PWM vem do próprio quadro de telemetria
  quando a opção "PWM de hardware" está ativa (ela é ligada automaticamente para
  `mVer >= 2`); caso contrário é estimado a partir de velocidade e tensão. Em ambos os
  casos o WheelLog expõe o valor em 0–100 %. Detalhes em
  [wheellog-integracao.md](wheellog-integracao.md).
- **Enquanto isso:** o motor recebe PWM em 0–100 % e satura fora dessa faixa.
- **Falta confirmar:** que a roda é mesmo um Nosfet Apex (o nome do repositório sugere,
  mas não foi dito) e a versão do firmware.

### Q3. O que é "crítico" para o vermelho
- **Bloqueia:** valores padrão de `SafetyConfig`.
- **Enquanto isso:** um alarme disparado pelo próprio app (WheelLog) é sempre crítico e
  não tem opção para desligar. Limiares próprios de PWM, velocidade, temperatura,
  corrente e tensão mínima existem e são configuráveis, mas vêm **todos desligados**:
  ligar qualquer um por padrão seria responder a esta pergunta.
- **A decidir:** quais grandezas, com que limiares, e se o padrão deve ser "seguir os
  alarmes do app" (comportamento atual) ou limiares independentes.

### Q4. Modo de transição de cores: é desejado? Quais cores?
- **Bloqueia:** exposição do modo na interface.
- **Enquanto isso:** existe no motor como `Pattern.ColorSequence`, marcado como proposta,
  com a sequência do exemplo (verde → vermelho → verde → azul), em versão suave e em
  degraus. Não é o padrão selecionado.
- **Atenção:** a sequência do exemplo passa por vermelho puro. Ver Q8.

### Q5. Só Android, ou também iOS?
- **Bloqueia:** nada do que foi feito. O motor de padrões, o protocolo e o controlador
  são Kotlin puro, sem Android, então podem ser aproveitados via Kotlin Multiplatform se
  o iOS entrar. A base WheelLog, porém, é só Android.

### Q6. URL do repositório WheelLog a usar como base
- **Bloqueia:** o fork e toda a integração de interface (fase 4).
- **Enquanto isso:** o levantamento dos pontos de integração foi feito sobre
  `github.com/Wheellog/Wheellog.Android`, commit `f44e39e` (30/09/2026), licença GPL-3.0,
  apenas como leitura. **Nenhum código do WheelLog foi copiado para este repositório.**
  Se a base for outro fork, o mapeamento precisa ser conferido de novo.

## Perguntas novas, surgidas no levantamento

### Q7. "Balanx" é o app "BanlanX"? E quem fabrica a placa?
- A pesquisa não encontrou nenhum app chamado "Balanx" nem placa LED fabricada pela
  Nosfet. Encontrou o app **BanlanX** (controladoras Bluetooth da série SP6xxE, da
  SP LED / Shenzhen Sperll) e a **Nosfet** como fabricante de monociclos (Apex, Aero, Aeon).
- A hipótese de trabalho é: roda Nosfet + controladora SP6xxE + app BanlanX. É só uma
  hipótese. Todo o levantamento de protocolo e de funções depende dela.
- **Bloqueia:** a spec inteira (R1) e o protocolo.

### Q8. Vermelho crítico x vermelho dos padrões
- O requisito diz que vermelho é o indicador crítico, mas o P1 termina em vermelho a
  100 % de PWM, o P2 permite escolher vermelho e a sequência proposta passa por ele.
  Na prática o usuário pode não distinguir "PWM alto" de "alerta".
- **Enquanto isso:** segue o requisito ao pé da letra (vermelho fixo). Existe a opção
  `CriticalStyle.FLASH` (vermelho piscando a 2 Hz, nunca apagado) para diferenciar.
- **A decidir:** manter fixo, usar piscante por padrão, ou reservar o vermelho puro.

### Q9. O que mostrar quando a telemetria some (roda desconectada)
- **Enquanto isso:** padrão avaliado com entrada zero (aceso, na ponta fria). Opções já
  implementadas: manter o último valor, ou apagar.
- **Por que importa:** se as fitas também servem para o usuário ser visto à noite,
  apagar pode ser indesejável.

### Q10. Faixa de velocidade quando a entrada é velocidade
- **Enquanto isso:** 0–50 km/h, configurável. O valor certo depende da roda.

### Q11. Licença do projeto
- O WheelLog é GPL-3.0. Ao incorporar código dele, o app resultante precisa ser
  distribuído sob GPL-3.0, com código-fonte disponível e avisos de copyright mantidos.
- **A decidir/confirmar:** que o projeto será GPL-3.0. Nenhum arquivo `LICENSE` foi
  adicionado ainda, porque é uma decisão do dono do repositório.

### Q12. O alerta crítico também se sobrepõe ao modo manual?
- O requisito fala em sobrepor "qualquer padrão". No modo manual (funções da própria
  placa, como no Balanx) o código **também** força o vermelho e depois restaura o que o
  usuário tinha escolhido. Confirmar se é o desejado.

### Q13. Limites adotados por segurança ou por limitação técnica
Valores escolhidos sem base no pedido, a confirmar:
- frequência de pulso limitada a 0,1–3 Hz (diretriz de fotossensibilidade: no máximo
  três flashes por segundo; também é o que um enlace BLE entrega com folga);
- o limite máximo de brilho não pode ser menor que 5 % (para que não sirva de meio de
  esconder o alerta);
- atraso do modo defasado limitado a 2 s por placa;
- suavização da entrada de 250 ms por padrão (não se aplica à detecção do alerta).

### Q14. Dez conexões BLE simultâneas no celular
- Muitos aparelhos Android limitam o número de conexões BLE simultâneas (o valor
  frequentemente citado é em torno de 7), e a roda ocupa uma. Dez placas + roda podem
  não caber em todo aparelho. O limite real só se descobre no aparelho-alvo.
- **A decidir, se o limite aparecer:** reduzir o número de placas, ou ligar várias fitas
  em uma mesma controladora.

### Q15. Convivência com o app oficial
- Essas controladoras aceitam uma conexão BLE por vez. Enquanto este app estiver
  conectado, o app oficial não conecta (e vice-versa). Confirmar se isso é aceitável.
