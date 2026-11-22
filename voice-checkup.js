/**
 * Checagens por voz: "Bolt, faz uma checagem no carro", "Bolt, tem alguma falha?", "Bolt, checa a bateria"...
 * Usa o que um adaptador ELM327 (ex.: Vgate iCar Pro) lê pelo OBD2 padrão da central do motor:
 * códigos de falha (armazenados, pendentes, permanentes), luz de injeção, monitores de prontidão,
 * freeze frame, sensores ao vivo, ajuste de combustível, sonda lambda, chassi (VIN) e o próprio adaptador.
 * ABS, airbag, câmbio e módulos da carroceria usam comandos de cada fabricante: ficam de fora.
 * Apagar códigos NÃO é feito por voz (só na tela de Diagnóstico, com confirmação).
 */
import { resolveLimits } from './alerts.js';
import { PARAMS } from './config.js';

const norm = (s) => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
const f0 = (n) => (n == null || !Number.isFinite(Number(n)) ? '—' : Math.round(Number(n)).toLocaleString('pt-BR'));
const f1 = (n) => (n == null || !Number.isFinite(Number(n)) ? '—' : Number(n).toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }));

/** Verbos de pedido: evitam que uma pergunta simples ("qual a temperatura?") vire uma checagem. */
const ASK = /(checa|checar|cheque|checagem|verifica|verificar|verificacao|analisa|analisar|analise|olha|olhar|examina|examinar|testa|testar|teste|escaneia|escanear|scanner|scan|confere|conferir|le |ler |leia|lê|relatorio|diagnostic|me (diz|fala|mostra)|mostra|como (esta|estao|ta|tao|anda|andam))/;

export const CHECKS = [
  { id: 'completo', label: 'Checagem completa', exemplo: 'Bolt, faz uma checagem completa no carro', rx: /(checagem|check.?up|revisao geral|escane|scanner|\bscan\b|diagnostico (completo|geral)|(verifica|analisa|olha|examina|confere|checa)r? (o |meu |todo o |tudo no )?(carro|veiculo|jeep|motor todo)|(ta|esta) tudo (ok|bem|certo|em ordem)( com o carro)?|faz um (exame|teste) geral)/, needsAsk: false },
  { id: 'falhas', label: 'Códigos de falha e luz de injeção', exemplo: 'Bolt, tem alguma falha no carro?', rx: /((tem|ha|existe|aparece|apareceu)( algum| alguma)? (codigo|falha|erro|defeito)|codigo(s)? de (falha|erro|defeito)|\bdtc\b|luz (da|de) injecao|luz do motor|check engine|injecao eletronica|(le|ler|leia|verifica|checa|mostra)r? (os |as )?(codigos|falhas|erros))/, needsAsk: false },
  { id: 'monitores', label: 'Monitores de prontidão (inspeção/emissões)', exemplo: 'Bolt, como estão os monitores?', rx: /(monitor|prontidao|inspecao|vistoria|emissoes|passa na (inspecao|vistoria))/, needsAsk: false },
  { id: 'freeze', label: 'Freeze frame (como o carro estava na hora da falha)', exemplo: 'Bolt, como estava o carro quando deu a falha?', rx: /(freeze|quadro congelado|quando (deu|aconteceu|apareceu) a falha|momento da falha|hora da falha)/, needsAsk: false },
  { id: 'bateria', label: 'Bateria e alternador', exemplo: 'Bolt, checa a bateria', rx: /(bateria|alternador|carga eletrica|parte eletrica|tensao|voltagem)/, needsAsk: true },
  { id: 'arrefecimento', label: 'Temperatura e arrefecimento', exemplo: 'Bolt, verifica o arrefecimento', rx: /(arrefecimento|radiador|superaquec|aquecimento do motor|temperatura(s)?)/, needsAsk: true },
  { id: 'mistura', label: 'Mistura de combustível e sonda lambda', exemplo: 'Bolt, analisa a mistura de combustível', rx: /(mistura|ajuste de combustivel|fuel trim|sonda|lambda|oxigenio|consumo (alto|demais)|gastando muito|bebendo muito)/, needsAsk: false },
  { id: 'sensores', label: 'Todos os sensores ao vivo', exemplo: 'Bolt, lê os sensores', rx: /(sensores|leituras|dados ao vivo|ao vivo|todos os dados|parametros)/, needsAsk: true },
  { id: 'chassi', label: 'Número do chassi (VIN)', exemplo: 'Bolt, qual o número do chassi?', rx: /(chassi|\bvin\b)/, needsAsk: false },
  { id: 'adaptador', label: 'Adaptador e conexão OBD2', exemplo: 'Bolt, verifica o adaptador', rx: /((verifica|checa|testa|como (esta|ta))r? (o |a )?(adaptador|vgate|obd|conexao|protocolo))/, needsAsk: false },
];

/** Qual checagem o motorista pediu (ou null). */
export function matchCheck(text) {
  const s = norm(text);
  if (/(apaga|apagar|limpa|limpar|zera|zerar|reseta|resetar).*(codigo|falha|erro|luz)/.test(s)) return 'apagar';
  if (/(o que (voce|vc) (sabe|consegue|pode) (checar|verificar|analisar|olhar|escanear)|quais (checagens|verificacoes|analises)|que checagens|opcoes de checagem|lista de checagens)/.test(s)) return 'menu';
  for (const c of CHECKS) {
    if (!c.rx.test(s)) continue;
    if (c.needsAsk && !ASK.test(s)) continue;
    return c.id;
  }
  return null;
}

/** Lista falada/escrita das checagens disponíveis. */
export function checkMenuText() {
  return ['Posso fazer estas checagens pelo OBD2:', ...CHECKS.map((c) => `• ${c.label} — "${c.exemplo}"`)].join('\n');
}

/* ------------------------------------------------------------------ leituras extras (modo 01) */

const signed = (b) => ((b - 128) * 100) / 128;
const EXTRA = {
  '06': { label: 'Ajuste curto de combustível (banco 1)', unit: '%', dec: (d) => signed(d[0]) },
  '07': { label: 'Ajuste longo de combustível (banco 1)', unit: '%', dec: (d) => signed(d[0]) },
  '0B': { label: 'Pressão do coletor (MAP)', unit: 'kPa', dec: (d) => d[0] },
  '0E': { label: 'Avanço de ignição', unit: '°', dec: (d) => d[0] / 2 - 64 },
  '14': { label: 'Sonda lambda B1S1', unit: 'V', dec: (d) => d[0] / 200 },
  '15': { label: 'Sonda lambda B1S2', unit: 'V', dec: (d) => d[0] / 200 },
};

async function readExtras(obd, pids) {
  const out = {};
  if (!obd || typeof obd.readRaw !== 'function' || !obd.isReady) return out;
  const supported = new Set((obd.supportedPids || []).map((p) => String(p).toUpperCase()));
  for (const pid of pids) {
    if (supported.size && !supported.has(pid)) continue;
    try {
      const data = await obd.readRaw(pid);
      if (data && data.length) out[pid] = EXTRA[pid].dec(data);
    } catch {
      /* não suportado neste carro */
    }
  }
  return out;
}

/* ------------------------------------------------------------------ análises */

function batteryCheck(engine, lim) {
  const v = engine.value('batteryVoltage');
  if (v == null) return { lines: ['Bateria: o adaptador não informou a tensão.'], issues: [] };
  const s = lim.voltageScale || 1;
  const running = engine.engineRunning ? engine.engineRunning() : (engine.value('rpm') || 0) > 400;
  const issues = [];
  let verdict;
  if (running) {
    if (v < 13.2 * s) { verdict = 'baixa com o motor ligado: o alternador pode não estar carregando'; issues.push({ sev: 2, text: `tensão de ${f1(v)} volts com o motor ligado, o alternador pode estar fraco` }); }
    else if (v > 15 * s) { verdict = 'alta demais: possível problema no regulador de tensão'; issues.push({ sev: 2, text: `tensão alta, ${f1(v)} volts, confira o regulador do alternador` }); }
    else verdict = 'normal, o alternador está carregando';
  } else if (v < 12.0 * s) { verdict = 'baixa: a bateria está descarregada ou fraca'; issues.push({ sev: 2, text: `bateria baixa, ${f1(v)} volts` }); }
  else if (v < 12.4 * s) { verdict = 'um pouco baixa, vale conferir a bateria'; issues.push({ sev: 1, text: `bateria um pouco baixa, ${f1(v)} volts` }); }
  else verdict = 'boa';
  return { lines: [`Bateria: ${f1(v)} V (${running ? 'motor ligado' : 'motor desligado'}) — ${verdict}.`], issues };
}

function coolingCheck(engine, lim) {
  const c = engine.value('coolantTemp');
  const i = engine.value('intakeTemp');
  const lines = [];
  const issues = [];
  if (c == null) lines.push('Temperatura do motor: sem leitura.');
  else {
    let v = 'normal';
    if (c >= (lim.coolantCrit ?? 112)) { v = 'CRÍTICA, pare em local seguro'; issues.push({ sev: 3, text: `motor superaquecido, ${f0(c)} graus` }); }
    else if (c >= (lim.coolantWarn ?? 105)) { v = 'alta, atenção'; issues.push({ sev: 2, text: `temperatura do motor alta, ${f0(c)} graus` }); }
    else if (c < 70) v = 'motor ainda frio';
    lines.push(`Temperatura do motor: ${f0(c)} °C — ${v}.`);
  }
  if (i != null) {
    const hi = i >= (lim.intakeWarn ?? 65);
    if (hi) issues.push({ sev: 1, text: `ar de admissão quente, ${f0(i)} graus` });
    lines.push(`Ar de admissão: ${f0(i)} °C${hi ? ' — quente' : ''}.`);
  }
  return { lines, issues };
}

async function mixtureCheck(obd) {
  const x = await readExtras(obd, ['06', '07', '14', '15', '0B', '0E']);
  const lines = [];
  const issues = [];
  if (!Object.keys(x).length) return { lines: ['Mistura de combustível: a central não informou o ajuste de combustível nem a sonda.'], issues, none: true };
  const st = x['06'];
  const lt = x['07'];
  if (st != null) lines.push(`${EXTRA['06'].label}: ${f1(st)} %.`);
  if (lt != null) lines.push(`${EXTRA['07'].label}: ${f1(lt)} %.`);
  const total = (st || 0) + (lt || 0);
  if (st != null || lt != null) {
    if (total > 20) issues.push({ sev: 2, text: `mistura pobre, a central está adicionando ${f0(total)} por cento de combustível; pode ser entrada falsa de ar, bico sujo ou bomba fraca` });
    else if (total < -20) issues.push({ sev: 2, text: `mistura rica, a central está tirando ${f0(-total)} por cento de combustível; pode ser bico pingando ou sensor com defeito` });
    else if (Math.abs(total) > 10) issues.push({ sev: 1, text: `ajuste de combustível um pouco fora, ${f0(total)} por cento` });
    else lines.push('Mistura: equilibrada.');
  }
  if (x['14'] != null) lines.push(`${EXTRA['14'].label}: ${f1(x['14'])} V (oscila entre 0,1 e 0,9 com o motor quente).`);
  if (x['15'] != null) lines.push(`${EXTRA['15'].label}: ${f1(x['15'])} V.`);
  if (x['0B'] != null) lines.push(`${EXTRA['0B'].label}: ${f0(x['0B'])} kPa.`);
  if (x['0E'] != null) lines.push(`${EXTRA['0E'].label}: ${f1(x['0E'])}°.`);
  return { lines, issues };
}

function sensorsList(engine) {
  const snap = engine.snapshot ? engine.snapshot() : null;
  const params = snap?.params || snap || {};
  const lines = [];
  for (const r of Object.values(params)) {
    if (!r || typeof r !== 'object' || !r.label) continue;
    if (r.validity === 'unsupported') continue;
    const val = r.value == null ? 'sem leitura' : `${Number(r.value).toLocaleString('pt-BR', { maximumFractionDigits: r.decimals ?? 1 })} ${r.unit || ''}`.trim();
    lines.push(`${r.label}: ${val}`);
  }
  return lines;
}

function dtcLines(scan) {
  const lines = [];
  if (scan.milOn != null) lines.push(`Luz de injeção: ${scan.milOn ? 'ACESA' : 'apagada'}.`);
  if (!scan.entries.length) lines.push('Nenhum código de falha na central.');
  for (const e of scan.entries) {
    const st = e.status === 'current' ? 'confirmado' : e.status === 'pending' ? 'pendente' : 'permanente';
    lines.push(`${e.code} (${st}${e.recurrent ? ', já apareceu antes' : ''}): ${e.description || 'sem descrição na base local; pesquise o código'}${e.manufacturerSpecific ? ' — código próprio do fabricante' : ''}.`);
  }
  return lines;
}

function monitorLines(scan) {
  if (!scan.monitors?.length) return ['Monitores: a central não informou.'];
  const pend = scan.monitors.filter((m) => !m.complete);
  return [
    `Monitores de prontidão: ${scan.monitors.length - pend.length} de ${scan.monitors.length} prontos.`,
    ...(pend.length ? [`Ainda incompletos: ${pend.map((m) => m.name).join(', ')} (normal logo depois de apagar códigos ou trocar a bateria; somem rodando alguns dias).`] : []),
  ];
}

/* ------------------------------------------------------------------ execução */

/**
 * Executa a checagem pedida.
 * deps: { ds, obd, diag, engine, getVehicle, alerts, maintenance, driver, tone }
 * Devolve { spoken, report, emotion }.
 */
export async function runCheck(id, deps) {
  const { ds, obd, diag, engine, getVehicle, driver } = deps;
  const d = driver || 'amigo';
  const deb = deps.tone === 'debochado';

  if (id === 'apagar') {
    const t = `Apagar códigos de falha eu não faço por voz, ${d}, por segurança. Vá em Diagnóstico e toque em "Limpar códigos". Antes, anote os códigos para o mecânico.`;
    return { spoken: t, report: t, emotion: 'neutro' };
  }
  if (id === 'menu') {
    const t = checkMenuText();
    return { spoken: `Posso checar falhas, luz de injeção, monitores, freeze frame, bateria, temperatura, mistura de combustível, sensores ao vivo, o chassi e o adaptador. Os exemplos estão na tela, ${d}.`, report: t, emotion: 'alegre' };
  }

  const src = ds.diagnosticSource();
  if (!src && id !== 'adaptador') {
    const t = deb ? `Sem o OBD2 conectado eu fico cego, ${d}. Conecta o adaptador e liga a ignição que eu faço a checagem.` : `Para fazer a checagem preciso do OBD2 conectado, ${d}. Conecte o adaptador e ligue a ignição.`;
    return { spoken: t, report: t, emotion: 'triste' };
  }
  const simulated = src?.origin === 'simulation';
  const lim = resolveLimits(getVehicle());
  const tag = simulated ? ' (dados simulados do Laboratório)' : '';
  const report = [];
  const issues = [];
  const add = (title, lines) => { report.push(`▶ ${title}`, ...lines, ''); };

  if (id === 'adaptador') {
    const st = obd.status || {};
    const lines = [
      `Estado: ${st.state === 'ready' ? 'conectado' : st.state || 'desconhecido'}.`,
      `Adaptador: ${st.adapter || st.adapterName || '—'}.`,
      `Protocolo do carro: ${st.protocol || '—'}.`,
      `Sensores que a central oferece: ${(st.supportedPids || []).length}.`,
    ];
    add('Adaptador OBD2', lines);
    const ok = st.state === 'ready';
    return { spoken: ok ? `O adaptador está conectado, ${d}, falando com a central pelo protocolo ${st.protocol || 'automático'}.` : `O adaptador não está conectado agora, ${d}.`, report: report.join('\n').trim(), emotion: ok ? 'alegre' : 'triste' };
  }

  let scan = null;
  const wantScan = ['completo', 'falhas', 'monitores', 'freeze'].includes(id);
  if (wantScan) {
    try {
      scan = await diag.scan();
    } catch (e) {
      const t = `Não consegui ler a central agora, ${d}: ${e?.message || 'sem resposta'}. Confira se a ignição está ligada.`;
      return { spoken: t, report: t, emotion: 'triste' };
    }
  }

  if (id === 'completo' || id === 'falhas') {
    add(`Códigos de falha${tag}`, dtcLines(scan));
    if (scan.milOn) issues.push({ sev: 3, text: 'a luz de injeção está acesa' });
    const cur = scan.entries.filter((e) => e.status === 'current' || e.status === 'permanent');
    const pen = scan.entries.filter((e) => e.status === 'pending');
    if (cur.length) issues.push({ sev: 3, text: `${cur.length === 1 ? 'um código de falha confirmado' : `${cur.length} códigos de falha confirmados`}: ${cur.map((e) => e.code).join(', ')}${cur[0].description ? `, ${cur[0].description}` : ''}` });
    if (pen.length) issues.push({ sev: 1, text: `${pen.length === 1 ? 'um código pendente' : `${pen.length} códigos pendentes`}, ${pen.map((e) => e.code).join(', ')}` });
  }
  if (id === 'completo' || id === 'monitores') {
    add('Monitores de prontidão', monitorLines(scan));
    const pend = (scan.monitors || []).filter((m) => !m.complete);
    if (id === 'monitores' && pend.length) issues.push({ sev: 1, text: `${pend.length} monitores ainda incompletos` });
  }
  if (id === 'freeze') {
    const ff = scan.freezeFrame;
    if (!ff) add('Freeze frame', ['Não há freeze frame gravado: a central não registrou nenhuma falha com foto dos sensores.']);
    else {
      const vals = Object.entries(ff.params || {}).filter(([, v]) => v && v.value != null).map(([k, v]) => `${PARAMS[k]?.label || k}: ${Number(v.value).toLocaleString('pt-BR', { maximumFractionDigits: PARAMS[k]?.decimals ?? 1 })} ${v.unit || ''}`.trim());
      add(`Freeze frame da falha ${ff.dtc || ''}`.trim(), [ff.info?.description ? `Falha: ${ff.info.description}.` : '', ...vals].filter(Boolean));
      const p = ff.params || {};
      const bits = [p.rpm ? `${f0(p.rpm.value)} rpm` : null, p.speed ? `${f0(p.speed.value)} quilômetros por hora` : null, p.coolantTemp ? `motor a ${f0(p.coolantTemp.value)} graus` : null, p.engineLoad ? `carga de ${f0(p.engineLoad.value)} por cento` : null].filter(Boolean);
      const t = `A falha ${ff.dtc || ''}${ff.info?.description ? `, ${ff.info.description},` : ''} foi registrada com ${bits.length ? bits.join(', ') : 'os dados que estão na tela'}, ${d}.`;
      return { spoken: t, report: report.join('\n').trim(), emotion: 'triste' };
    }
    return { spoken: `Não há freeze frame gravado, ${d}. A central não registrou falha com foto dos sensores.`, report: report.join('\n').trim(), emotion: 'alegre' };
  }
  if (id === 'completo' || id === 'bateria') {
    const b = batteryCheck(engine, lim);
    add('Bateria e alternador', b.lines);
    issues.push(...b.issues);
  }
  if (id === 'completo' || id === 'arrefecimento') {
    const c = coolingCheck(engine, lim);
    add('Temperaturas', c.lines);
    issues.push(...c.issues);
  }
  if (id === 'completo' || id === 'mistura') {
    if (simulated) add('Mistura de combustível', ['Disponível só com o carro de verdade (o Laboratório não simula o ajuste de combustível).']);
    else {
      const m = await mixtureCheck(obd);
      add('Mistura de combustível e sonda', m.lines);
      issues.push(...m.issues);
    }
  }
  if (id === 'sensores') {
    const lines = sensorsList(engine);
    add(`Sensores ao vivo${tag}`, lines.length ? lines : ['Nenhuma leitura no momento.']);
  }
  if (id === 'chassi') {
    let vin = null;
    try { vin = await src.source.readVin?.(); } catch { vin = null; }
    const v = typeof vin === 'string' ? vin : vin?.vin || null;
    add('Chassi (VIN)', [v ? `VIN: ${v}` : 'A central deste carro não informa o número do chassi pelo OBD2 (comum em carros mais antigos).']);
    return { spoken: v ? `O chassi informado pela central é ${v.split('').join(' ')}.` : `A central não informa o chassi pelo OBD2, ${d}. Ele está no documento do carro e gravado no vidro.`, report: report.join('\n').trim(), emotion: 'neutro' };
  }
  if (id === 'completo') {
    const al = (deps.alerts || []).filter((a) => a && a.level && a.level !== 'info');
    if (al.length) {
      add('Alertas ativos', al.map((a) => `${a.title}${a.message ? `: ${a.message}` : ''}`));
      for (const a of al) issues.push({ sev: a.level === 'critical' ? 3 : 2, text: a.title });
    }
    const due = (deps.maintenance || []).filter((m) => m.status === 'overdue' || m.status === 'soon');
    if (due.length) {
      add('Manutenção', due.map((m) => `${m.name}: ${m.status === 'overdue' ? 'VENCIDA' : 'vence em breve'}${m.detail ? ` — ${m.detail}` : ''}`));
      for (const m of due.filter((x) => x.status === 'overdue')) issues.push({ sev: 1, text: `${m.name} vencida` });
    }
  }

  // ---------- resumo falado + emoção
  issues.sort((a, b) => b.sev - a.sev);
  const worst = issues[0]?.sev || 0;
  const what = CHECKS.find((c) => c.id === id)?.label.toLowerCase() || 'checagem';
  let spoken;
  if (id === 'sensores') spoken = `Li os sensores, ${d}. Os valores estão na tela.`;
  else if (!issues.length) {
    spoken = id === 'completo'
      ? (deb ? `Checagem feita, ${d}: tô inteirão! Nenhuma falha, temperatura e bateria em ordem.` : `Checagem completa, ${d}. Nenhuma falha na central, temperatura e bateria em ordem. Tudo certo.`)
      : `Verifiquei ${what}, ${d}: está tudo certo.`;
  } else {
    const top = issues.slice(0, 3).map((x) => x.text);
    const intro = worst >= 3 ? (deb ? `Opa, ${d}, achei coisa séria:` : `Atenção, ${d}. Encontrei problemas:`) : `Encontrei ${issues.length === 1 ? 'um ponto' : 'alguns pontos'} de atenção, ${d}:`;
    spoken = `${intro} ${top.join('; ')}.${issues.length > 3 ? ` E mais ${issues.length - 3} no relatório.` : ''} O relatório completo está na tela.`;
  }
  const header = `Relatório: ${CHECKS.find((c) => c.id === id)?.label || 'Checagem'}${tag} — ${new Date().toLocaleString('pt-BR')}`;
  const summary = issues.length ? ['Pontos de atenção:', ...issues.map((x) => `${x.sev >= 3 ? '🔴' : x.sev === 2 ? '🟠' : '🟡'} ${x.text}`), ''] : ['✅ Nenhum problema encontrado.', ''];
  const emotion = worst >= 2 ? 'triste' : 'alegre';
  return { spoken, report: [header, '', ...summary, ...report].join('\n').trim(), emotion };
}
