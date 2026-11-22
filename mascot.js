/**
 * Mascote da ALMA DO CARRO: os dois olhos angulares do carro que acendem quando o motor liga,
 * piscam ao pensar, pulsam ao falar e ficam âmbar/vermelhos em alertas.
 * Estados: sleeping (motor desligado), normal, listening, thinking, speaking, attention, alert.
 * As animações vivem em styles.css (seletor .bolt-mascot[data-state]).
 */

export const MASCOT_STATES = ['sleeping', 'normal', 'listening', 'thinking', 'speaking', 'attention', 'alert'];

export const MASCOT_LABELS = {
  sleeping: 'Em espera',
  normal: 'Pronto',
  listening: 'Ouvindo',
  thinking: 'Pensando',
  speaking: 'Falando',
  attention: 'Atenção',
  alert: 'Alerta',
};

const BARS = [18, 23, 28, 33, 38, 43, 48];

/** Marcação interna do mascote (viewBox 0 0 64 56): olhos angulares + ondas de voz. */
export function mascotInner() {
  const bars = BARS.map((x, i) => `<rect class="m-bar" style="--i:${i}" x="${x - 1.4}" y="40" width="2.8" height="10" rx="1.4"/>`).join('');
  return (
    '<polygon class="m-eye m-eye-l" points="2,12 29,19 26,34 4,28"/>' +
    '<polygon class="m-eye m-eye-r" points="62,12 35,19 38,34 60,28"/>' +
    `<g class="m-mouth">${bars}</g>`
  );
}

/** SVG estático (estado normal) para o arquivo bolt-mascot.svg. */
export function mascotSvgString() {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 56" width="256" height="224">
<rect width="64" height="56" rx="10" fill="#07131C"/>
<polygon points="2,12 29,19 26,34 4,28" fill="#3EE2FF"/><polygon points="62,12 35,19 38,34 60,28" fill="#3EE2FF"/>
</svg>`;
}

/* ---------------------------------------------------------------- estilo do rosto
 * 'robo'  = os olhos angulares originais.
 * 'carro' = "carro falante": a foto do próprio carro, com os faróis acendendo e piscando como olhos
 *           e a entrada de ar do para-choque abrindo como boca enquanto o assistente fala.
 */
let avatar = { style: 'robo', file: null, face: null };
let emotion = 'neutro';

/** Emoção mostrada em todos os mascotes: neutro | alegre | triste | bravo. */
export function setMascotEmotion(e) {
  emotion = ['alegre', 'triste', 'bravo'].includes(e) ? e : 'neutro';
  for (const w of [...live]) {
    if (w._seen && !w.isConnected) { live.delete(w); continue; }
    w.dataset.emotion = emotion;
  }
}
const live = new Set();

/** Troca o rosto de todos os mascotes na tela. */
export function setMascotAvatar(next = {}) {
  avatar = { ...avatar, ...next };
  for (const w of [...live]) {
    if (w._seen && !w.isConnected) { live.delete(w); continue; }
    renderFace(w);
  }
}

function useCar(wrap) {
  return avatar.style === 'carro' && !!avatar.file && !!avatar.face && !wrap._carFailed && Number(wrap.dataset.size) >= 40;
}

function place(el, r) {
  el.style.left = `${r.x - r.w / 2}%`;
  el.style.top = `${r.y - r.h / 2}%`;
  el.style.width = `${r.w}%`;
  el.style.height = `${r.h}%`;
}

function renderFace(wrap) {
  if (wrap.isConnected) wrap._seen = true;
  const car = useCar(wrap);
  wrap.classList.toggle('is-car', car);
  wrap.replaceChildren();
  if (car) {
    const body = document.createElement('span');
    body.className = 'cm-body';
    const img = document.createElement('img');
    img.className = 'cm-img';
    img.alt = '';
    img.draggable = false;
    img.decoding = 'async';
    img.onerror = () => { wrap._carFailed = true; renderFace(wrap); };
    img.src = avatar.file;
    body.appendChild(img);
    avatar.face.eyes.forEach((e, i) => {
      const eye = document.createElement('i');
      eye.className = `cm-eye cm-eye-${i}`;
      place(eye, e);
      body.appendChild(eye);
    });
    const mouth = document.createElement('i');
    mouth.className = 'cm-mouth';
    place(mouth, avatar.face.mouth);
    mouth.appendChild(document.createElement('b'));
    body.appendChild(mouth);
    wrap.appendChild(body);
    return;
  }
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '0 0 64 56');
  svg.setAttribute('aria-hidden', 'true');
  const doc = new DOMParser().parseFromString(`<svg xmlns="${NS}">${mascotInner()}</svg>`, 'image/svg+xml');
  for (const n of [...doc.documentElement.childNodes]) svg.appendChild(document.importNode(n, true));
  wrap.appendChild(svg);
}

/**
 * Cria o elemento do mascote. Retorna o <span> com método setState().
 * @param {{size?:number,state?:string}} opts
 */
export function createMascot({ size = 44, state = 'normal' } = {}) {
  const wrap = document.createElement('span');
  wrap.className = 'bolt-mascot';
  wrap.style.setProperty('--ms', `${size}px`);
  wrap.dataset.size = String(size);
  wrap.dataset.emotion = emotion;
  live.add(wrap);
  renderFace(wrap);
  wrap.setState = (s) => {
    const next = MASCOT_STATES.includes(s) ? s : 'normal';
    wrap.dataset.state = next;
    wrap.setAttribute('role', 'img');
    wrap.setAttribute('aria-label', `Assistente: ${MASCOT_LABELS[next]}`);
  };
  wrap.setState(state);
  return wrap;
}
