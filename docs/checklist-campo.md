# Checklist de testes em campo

Para executar com a roda, as placas e o celular-alvo. Cada item tem o resultado
esperado; anote o que aconteceu de fato. Os itens marcados com **(S)** têm relação com
segurança e devem ser feitos primeiro com a roda suspensa ou parada.

Antes de começar, anote: modelo e firmware da roda, modelo de cada placa, modelo e
versão de Android do celular, versão deste app.

## A. Bancada (roda parada ou suspensa)

### A1. Identificação e conexão
- [ ] A placa aparece na busca. Anotar nome anunciado e modelo.
- [ ] Conecta com o protocolo `sp63x`. Se não responder, tentar `sp61x-pwm`. Anotar qual funciona.
- [ ] O status é lido (ligado/desligado, modo, firmware). Se não for, anotar os bytes recebidos.
- [ ] Com o app oficial aberto e conectado, este app não conecta, e vice-versa (esperado: uma conexão por vez).
- [ ] Fechar e reabrir o app: placas, nomes e padrões voltam como estavam.

### A2. Protocolo e cor
- [ ] Vermelho, verde e azul puros aparecem na cor certa (se trocados, a ordem dos canais da placa está diferente).
- [ ] Brilho 100 %, 50 %, 25 % e 5 %: cada passo é visivelmente menor que o anterior.
- [ ] Brilho 0: a fita apaga de fato?
- [ ] Limite máximo de brilho em 40 %: nenhum padrão, nem o alerta, passa disso (comparar lado a lado com 100 %).
- [ ] Medir a corrente da fita no limite máximo e conferir com a fonte e a fiação.
- [ ] Gradiente P1 varrido de 0 a 100 %: transição contínua, sem saltos nem piscadas.
- [ ] Pulso a 3 Hz por um minuto: a placa acompanha sem atrasar nem travar.
- [ ] Deixar um padrão rodando por 30 minutos: a placa continua respondendo e não aquece além do normal.
- [ ] Desligar e religar a alimentação da placa depois de um padrão: em que estado ela volta? (indica se grava cada cor na memória)

### A3. Funções da placa (paridade)
- [ ] Ligar/desligar, cor fixa, brilho.
- [ ] Efeitos dinâmicos: escolher, velocidade.
- [ ] Modo música e sensibilidade (se a placa tiver microfone).
- [ ] Estado ao energizar: desligado, ligado, último.
- [ ] Depois de usar uma função da placa, voltar ao modo padrão: a cor da telemetria reaparece.

### A4. Alerta crítico **(S)**
- [ ] Simular a condição crítica (limiar baixo de PWM ou velocidade com a roda suspensa): todas as placas ficam vermelhas.
- [ ] Medir o tempo entre o alarme do app (som/vibração) e o vermelho. Anotar.
- [ ] Com cada padrão ativo (P1, P2, P3, transição), o vermelho se sobrepõe.
- [ ] Com brilho geral em 0 %, o vermelho ainda aparece.
- [ ] No modo defasado com atraso máximo, todas as placas ficam vermelhas juntas.
- [ ] No modo manual com a placa desligada, o vermelho aparece; depois a placa volta a ficar desligada.
- [ ] Ao cessar a condição, o padrão volta após o tempo de retenção.
- [ ] **O som, a vibração e os avisos na tela do app continuam iguais com os LEDs ligados e com eles desligados.**
- [ ] Com a tela do celular apagada e o app em segundo plano, o alarme do app e o vermelho continuam funcionando.

### A5. Várias placas
- [ ] Adicionar placas uma a uma até 10. Anotar em qual número o celular para de conectar (limite do aparelho, Q14).
- [ ] A 11ª é recusada com mensagem clara.
- [ ] Renomear uma placa: o nome muda, o endereço e a conexão não.
- [ ] Sincronizado: todas mudam juntas (filmar em câmera lenta se necessário).
- [ ] Defasado: a onda percorre as placas na ordem da lista; reordenar muda a ordem.
- [ ] Com todas conectadas, a conexão com a roda continua estável e a telemetria não atrasa.

## B. Em movimento (área segura, velocidade baixa primeiro)

### B1. Padrões
- [ ] P1 por PWM: a cor acompanha aceleração e frenagem sem atraso perceptível.
- [ ] P1 por velocidade: a cor acompanha o velocímetro; ajustar a velocidade máxima da faixa.
- [ ] Trocar de padrão em movimento (com um ajudante ou parado entre trechos): a troca é imediata e não há piscada estranha.
- [ ] Trocar a entrada (velocidade/PWM) em movimento.
- [ ] A suavização padrão (250 ms) é agradável? Anotar o valor preferido.

### B2. Alerta crítico em movimento **(S)**
- [ ] Com um limiar baixo e seguro (por exemplo, velocidade de 15 km/h), cruzar o limiar: vermelho imediato em todas as placas.
- [ ] O vermelho é distinguível da ponta quente do P1? Se não, testar o estilo piscante (Q8).
- [ ] Um observador externo confirma o que viu e quando.

### B3. Desconexão e reconexão
- [ ] Desligar uma placa em movimento: as outras continuam normais.
- [ ] Religar: ela volta sozinha e mostra a cor atual. Anotar o tempo.
- [ ] Afastar o celular até perder as placas e voltar: todas reconectam.
- [ ] Desligar o Bluetooth do celular e religar.
- [ ] Perder a conexão com a roda (desligar a roda parado): as placas vão para o estado "sem telemetria" configurado.
- [ ] Reconectar a roda: o padrão volta.
- [ ] Placa perde o celular durante um alerta: em que cor ela fica? (ela mantém o último comando; anotar se isso é aceitável)
- [ ] Encerrar o app à força com placas acesas: em que estado elas ficam?

### B4. Uso prolongado
- [ ] Um trajeto de pelo menos 30 minutos com todas as placas: anotar desconexões, consumo de bateria do celular e aquecimento.
- [ ] Mesmo trajeto com gravação de log do WheelLog ligada: nenhuma perda de dados no log.

## C. Registro

Para cada falha, anotar: item, horário, o que foi observado, e se possível o log do app
e um log HCI Bluetooth do Android.
