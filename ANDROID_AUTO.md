# Alma do Carro no Android Auto

O Android Auto só mostra na multimídia **apps Android instalados** (APK) que usam a biblioteca oficial
"Android for Cars" e declaram uma categoria permitida. Página web/PWA nunca aparece lá, por isso o
painel não aparecia. A pasta `android/` é esse app: ele roda **o mesmo projeto web, sem mudanças**, e:

- aparece no menu do Android Auto como app de **navegação** (a única categoria que pode desenhar a
  própria tela) e mostra o painel completo na multimídia, com toque e rolagem;
- botões na multimídia: **Bolt** (chamar o Bolt) e **olhos** (modo espera);
- **voz nativa do Android**: o Bolt ouve o nome e responde com o celular em segundo plano, tela
  apagada ou Spotify tocando;
- quando você diz "Bolt", o **Spotify pausa** enquanto você fala e **abaixa o volume** enquanto o Bolt
  responde, e depois volta sozinho;
- se a multimídia estiver mostrando outro app, a resposta e os alertas aparecem **por cima, como aviso**
  (com botão "Abrir");
- OBD2 pelo próprio app: "Bluetooth clássico" (ELM327 pareado) e "Ponte nativa" (ELM327 BLE ou Wi-Fi
  192.168.0.10:35000).

## Versões por carro

O mesmo projeto gera um app para cada carro (o painel é o mesmo; muda só o cadastro inicial):

- **AlmaDoCarro.apk** – "Alma do Carro", Jeep Renegade 2016 (Jorge).
- **AlmaDaEcoSport.apk** – "Alma da EcoSport", Ford EcoSport 2006 1.6 8V Zetec RoCam Flex, manual (Jean).
  Já vem com o carro cadastrado (faixa vermelha 6000 rpm, tanque de 45 L) e o adaptador OBD tenta
  primeiro o protocolo J1850 PWM, usado pelos Ford da época. Alguns ELM327 baratos (os "v2.1")
  não têm esse protocolo: se não conectar, use um ELM327 **v1.5** ou um OBDLink/Vgate.

Para outro carro: copie `android/app/src/ecosport/` com outro nome, ajuste `assets/native/profile.js`
e crie a versão em `productFlavors` no `android/app/build.gradle.kts`.

## 1. Gerar o APK

**Opção A, sem instalar nada (GitHub):** envie a pasta do projeto inteira (com `android/` e `.github/`)
para um repositório no GitHub. Na aba **Actions**, o processo "APK Android (Alma do Carro)" roda sozinho.
Quando terminar (uns 5 min), abra a execução e baixe **AlmaDoCarro-apk**.

**Opção B (Android Studio):** File > Open > pasta `android`. Depois Build > Build App Bundle(s) / APK(s) >
Build APK(s). O arquivo sai em `android/app/build/outputs/apk/`.

A cada build, o projeto web da pasta principal é copiado para dentro do APK. Mudou o painel? É só gerar de novo.

## 2. Instalar de um jeito que o Android Auto aceite

O Android Auto **ignora apps deste tipo instalados "por fora"**, mesmo com "Fontes desconhecidas"
ligado: ele exige que o app venha da Play Store. Existem dois caminhos:

**Caminho 1 – pelo computador (grátis):**
1. No celular: Configurações > Sobre o telefone > toque 7× em "Número da versão". Depois, em Opções do
   desenvolvedor, ligue **Depuração USB**.
2. No computador, instale o "Android SDK Platform-Tools" (tem o `adb`), ligue o celular no cabo e rode:
   ```
   adb install -r -i com.android.vending AlmaDoCarro.apk
   ```
   O `-i com.android.vending` registra a Play Store como origem da instalação, que é o que o Android Auto confere.
   Se der erro de assinatura, desinstale a versão antiga primeiro.

**Caminho 2 – pela Play Store (US$ 25, uma vez):** crie uma conta no Google Play Console, envie o app em
**Teste interno** e instale pelo link de teste. Não passa por revisão pública.

## 3. Liberar no Android Auto

1. Abra as configurações do Android Auto no celular (Configurações > Apps > Android Auto > Configurações
   adicionais no app).
2. Toque 10× em **Versão** e confirme para ativar o modo desenvolvedor. No menu ⋮ > Configurações do
   desenvolvedor, ligue **Fontes desconhecidas**.
3. Em **Personalizar o acesso rápido / menu de apps**, deixe **Alma do Carro** marcado.

## 4. Primeiro uso

1. Abra o **Alma do Carro no celular** uma vez e aceite as permissões: microfone, dispositivos por perto
   (Bluetooth), localização, notificações, "não otimizar bateria" e, se quiser, "sobrepor a outros apps"
   (para o Bolt aparecer no celular quando chamado sem Android Auto).
2. No app, vá em Configurações > Assistente: ligue **Microfone** e, em **Modo de escuta**, escolha **Contínuo por nome**.
3. Conecte no carro e abra **Alma do Carro** na multimídia.

Uma notificação fixa "Bolt está com você" indica que ele está ouvindo em segundo plano. Para desligar,
toque em **Encerrar** nela.

## Limites que dependem do Android

- O Android Auto **não deixa nenhum app tomar a tela sozinho**. Quando você chama o Bolt com o Spotify
  na tela, ele responde por voz e mostra o aviso por cima. Para ver o painel, toque em "Abrir".
- Música alta no carro atrapalha qualquer microfone. Se o Bolt não ouvir de primeira, fale o nome um
  pouco mais alto ou use o botão **Bolt** da multimídia.
- Em alguns celulares o reconhecimento de voz do Google faz um "bip" a cada reinício da escuta. No
  Android 13 ou mais novo o app usa o reconhecimento no próprio aparelho quando existe, que costuma ser
  silencioso e funciona sem internet (o pacote de português precisa estar baixado em Configurações >
  Sistema > Idioma > Reconhecimento de voz).
- Depois de reiniciar o celular, abra o app no celular uma vez: o Android não deixa um app ligar o
  microfone em segundo plano sem isso.
- A chave de assinatura (`android/app/alma.keystore`, senha `almadocarro`) é para uso pessoal. Para
  publicar na Play Store, troque por uma chave sua e guarde-a.
