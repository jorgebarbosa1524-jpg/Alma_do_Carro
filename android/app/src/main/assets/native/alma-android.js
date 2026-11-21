/*
 * Alma do Carro — ponte do app Android.
 * Carregado antes do app.js somente dentro do app Android. Não altera o projeto web:
 * apenas oferece, com recursos nativos do Android, o que o WebView não tem
 * (voz, Bluetooth/Wi-Fi do OBD2) e liga o painel à tela do Android Auto.
 */
(function () {
  'use strict';
  var A = window.AlmaAndroid;
  if (!A) return;
  var N = (window.__almaNative = window.__almaNative || {});

  function parse(s, d) { try { return s ? JSON.parse(s) : d; } catch (e) { return d; } }
  function listen(target, type, fn) { (target._l[type] = target._l[type] || []).push(fn); }
  function unlisten(target, type, fn) { var a = target._l[type]; if (a) { var i = a.indexOf(fn); if (i >= 0) a.splice(i, 1); } }
  function fire(target, type, extra) {
    var ev = extra || {};
    ev.type = type;
    ev.target = target;
    try { if (typeof target['on' + type] === 'function') target['on' + type](ev); } catch (e) { console.error(e); }
    var list = (target._l && target._l[type]) || [];
    list.slice().forEach(function (fn) { try { fn.call(target, ev); } catch (e) { console.error(e); } });
  }

  /* No app nativo a fala não depende de um toque na tela (o navegador exige; aqui não). */
  try {
    Object.defineProperty(navigator, 'userActivation', { configurable: true, get: function () { return { hasBeenActive: true, isActive: true }; } });
  } catch (e) { /* ignora */ }

  /* ============ DIAGNÓSTICO DE VOZ (linha discreta no rodapé, some sozinha) ============ */
  var diagEl = null, diagT = null;
  N.diag = function (msg) {
    try {
      if (!document.body) return;
      if (!diagEl) {
        diagEl = document.createElement('div');
        diagEl.style.cssText = 'position:fixed;left:8px;right:8px;bottom:8px;z-index:2147483647;padding:6px 10px;border-radius:8px;' +
          'background:rgba(0,0,0,.78);color:#9fe8ff;font:12px/1.35 monospace;pointer-events:none;white-space:pre-wrap;';
        document.body.appendChild(diagEl);
      }
      var info = parse(A.voiceInfo ? A.voiceInfo() : '{}', {});
      diagEl.textContent = '[voz ' + (info.version || '') + '] ' + msg + '\nescuta: ' + (info.escuta || '?') + ' | fala: ' + (info.fala || '?');
      diagEl.style.display = 'block';
      clearTimeout(diagT);
      diagT = setTimeout(function () { diagEl.style.display = 'none'; }, 6000);
    } catch (e) { /* ignora */ }
  };

  /* =============================== FALA (speechSynthesis) =============================== */
  function SpeechSynthesisVoice(o) {
    this.name = o.name; this.voiceURI = o.name; this.lang = o.lang;
    this.localService = !!o.localService; this['default'] = !!o['default'];
  }
  var voiceList = parse(A.ttsVoices(), []).map(function (v) { return new SpeechSynthesisVoice(v); });
  var utts = {};
  var useq = 0;
  var currentU = null;

  function SpeechSynthesisUtterance(text) {
    this.text = text == null ? '' : String(text);
    this.lang = 'pt-BR'; this.voice = null; this.rate = 1; this.pitch = 1; this.volume = 1;
    this.onstart = this.onend = this.onerror = this.onpause = this.onresume = this.onboundary = this.onmark = null;
    this._l = {};
  }
  SpeechSynthesisUtterance.prototype.addEventListener = function (t, f) { listen(this, t, f); };
  SpeechSynthesisUtterance.prototype.removeEventListener = function (t, f) { unlisten(this, t, f); };

  var synth = {
    speaking: false, pending: false, paused: false, onvoiceschanged: null, _l: {},
    getVoices: function () { return voiceList.slice(); },
    speak: function (u) {
      var id = 'u' + ++useq;
      utts[id] = u;
      currentU = id;
      synth.pending = true;
      var vol = u.volume == null ? 1 : Number(u.volume);
      A.ttsSpeak(id, String(u.text || ''), String(u.lang || 'pt-BR'), u.voice ? String(u.voice.name) : '', Number(u.rate) || 1, Number(u.pitch) || 1, isNaN(vol) ? 1 : vol);
    },
    cancel: function () { A.ttsCancel(); },
    pause: function () {}, resume: function () {},
    addEventListener: function (t, f) { listen(synth, t, f); },
    removeEventListener: function (t, f) { unlisten(synth, t, f); },
  };
  N.tts = function (id, type, err) {
    var u = utts[id];
    if (!u) return;
    if (type === 'start') {
      synth.speaking = true; synth.pending = false;
      fire(u, 'start', { utterance: u });
      return;
    }
    delete utts[id];
    if (currentU === id) { synth.speaking = false; synth.pending = false; currentU = null; }
    if (type === 'error' && err !== 'interrupted') N.diag('erro da fala: ' + err);
    if (type === 'end') fire(u, 'end', { utterance: u });
    else fire(u, 'error', { utterance: u, error: err || 'synthesis-failed' });
  };
  N.voices = function (json) {
    voiceList = parse(json, []).map(function (v) { return new SpeechSynthesisVoice(v); });
    fire(synth, 'voiceschanged');
  };
  try { Object.defineProperty(window, 'speechSynthesis', { configurable: true, get: function () { return synth; } }); } catch (e) { window.speechSynthesis = synth; }
  window.SpeechSynthesisUtterance = SpeechSynthesisUtterance;
  window.SpeechSynthesisVoice = SpeechSynthesisVoice;

  /* ============================ ESCUTA (SpeechRecognition) ============================ */
  var recs = {};
  var rseq = 0;
  var wakeFn = null;
  var lastWakeSignal = 0;
  var lastErrAt = {};
  import('/assets/www/voice.js').then(function (m) { wakeFn = m.extractAfterWake; }).catch(function () {});

  function wakeName() {
    try { return (window.__alma && window.__alma.settings.get('assistant.name', 'Bolt')) || 'Bolt'; } catch (e) { return 'Bolt'; }
  }
  function checkWake(text) {
    if (!text) return;
    var heard = wakeFn ? wakeFn(text, wakeName()) !== null : new RegExp('\\b' + wakeName() + '\\b', 'i').test(text);
    if (heard && Date.now() - lastWakeSignal > 4000) { lastWakeSignal = Date.now(); A.wakeHeard(); N.diag('ouvi o nome "' + wakeName() + '"'); }
  }

  function SpeechRecognition() {
    this.lang = 'pt-BR'; this.interimResults = false; this.continuous = false; this.maxAlternatives = 1; this.grammars = null;
    this.onstart = this.onend = this.onresult = this.onerror = this.onnomatch = this.onaudiostart = this.onaudioend = this.onspeechstart = this.onspeechend = this.onsoundstart = this.onsoundend = null;
    this._l = {}; this._id = null; this._run = false; this._started = false; this._ended = false; this._results = [];
  }
  SpeechRecognition.prototype.addEventListener = function (t, f) { listen(this, t, f); };
  SpeechRecognition.prototype.removeEventListener = function (t, f) { unlisten(this, t, f); };
  SpeechRecognition.prototype.start = function () {
    if (this._run) throw new DOMException('O reconhecimento já foi iniciado.', 'InvalidStateError');
    this._run = true; this._ended = false; this._started = false; this._results = [];
    this._session();
  };
  SpeechRecognition.prototype._session = function () {
    this._t0 = Date.now();
    this._heard = false;
    var id = 'r' + ++rseq;
    this._id = id;
    recs[id] = this;
    A.sttStart(id, String(this.lang || 'pt-BR'), !!(this.interimResults || this.continuous), String(wakeName()));
  };
  SpeechRecognition.prototype.stop = function () {
    if (!this._run) return;
    this._run = false;
    if (this._id) A.sttStop(this._id); else this._finish();
  };
  SpeechRecognition.prototype.abort = function () {
    var id = this._id;
    this._run = false;
    if (id) A.sttAbort(id); else this._finish();
  };
  SpeechRecognition.prototype._finish = function () {
    if (this._ended) return;
    this._ended = true;
    fire(this, 'end');
  };
  SpeechRecognition.prototype._emitResult = function (text, isFinal) {
    var list = this._results;
    var last = list[list.length - 1];
    var entry = [{ transcript: text, confidence: isFinal ? 0.9 : 0 }];
    entry.isFinal = !!isFinal;
    entry.item = function (i) { return this[i]; };
    var index;
    if (last && !last.isFinal) { list[list.length - 1] = entry; index = list.length - 1; }
    else { list.push(entry); index = list.length - 1; }
    if (!isFinal && !this.interimResults) return;
    var results = list.slice();
    results.item = function (i) { return this[i]; };
    fire(this, 'result', { resultIndex: index, results: results });
  };

  N.stt = function (id, type, a, isFinal, altsJson) {
    var r = recs[id];
    if (!r) return;
    if (type === 'start') {
      if (!r._started) { r._started = true; fire(r, 'start'); fire(r, 'audiostart'); }
      return;
    }
    if (type === 'result') {
      r._heard = true;
      // Entre as interpretações do Google, prefere a que tem o nome do assistente.
      var alts = parse(altsJson, null);
      if (alts && alts.length > 1 && wakeFn) {
        var name = wakeName();
        if (wakeFn(a, name) === null) {
          for (var k = 0; k < alts.length; k++) { if (wakeFn(alts[k], name) !== null) { a = alts[k]; break; } }
        }
      }
      N.diag((isFinal ? 'entendi: "' : 'ouvindo: "') + a + '"');
      checkWake(a);
      r._emitResult(a, isFinal);
      return;
    }
    if (type === 'error') {
      var code = a || 'aborted';
      // Erro 7 depois de um texto provisório: usa esse texto como frase final em vez do erro.
      if (code === 'no-speech') {
        var pl = r._results, pend = pl[pl.length - 1];
        if (pend && !pend.isFinal && pend[0] && String(pend[0].transcript || '').trim()) {
          N.diag('entendi: "' + pend[0].transcript + '"');
          r._emitResult(String(pend[0].transcript), true);
          return;
        }
      }
      // Escuta contínua: silêncio e reinícios são normais, não viram aviso na tela.
      if (r.continuous && r._run && (code === 'no-speech' || code === 'aborted')) return;
      if (r.continuous && r._run && code === 'network') {
        if (Date.now() - (lastErrAt[code] || 0) < 60000) return;
        lastErrAt[code] = Date.now();
      }
      if (code === 'not-allowed' || code === 'service-not-allowed' || code === 'audio-capture') r._run = false;
      fire(r, 'error', { error: code, message: '' });
      return;
    }
    if (type === 'end') {
      delete recs[id];
      if (r._id !== id) return;
      r._id = null;
      // O Google às vezes mostra a frase só como texto provisório e encerra com erro 7, sem
      // confirmar. Nesse caso, a última frase entendida vale como frase final.
      var list = r._results, last = list[list.length - 1];
      if (last && !last.isFinal && last[0] && String(last[0].transcript || '').trim() && (r._run || !r.continuous)) {
        N.diag('entendi: "' + last[0].transcript + '"');
        r._emitResult(String(last[0].transcript), true);
      }
      if (r.continuous && r._run) {
        // Se o reconhecimento falhar logo de cara, espera cada vez mais antes de tentar de novo
        // (evita um laço de reinícios que trava o app).
        var quick = !r._heard && Date.now() - (r._t0 || 0) < 1500;
        r._backoff = quick ? Math.min((r._backoff || 150) * 2, 5000) : 120;
        setTimeout(function () { if (r._run && !r._id) r._session(); }, r._backoff);
        return;
      }
      r._run = false;
      r._finish();
    }
  };
  window.SpeechRecognition = SpeechRecognition;
  window.webkitSpeechRecognition = SpeechRecognition;

  /* ================================ OBD2 (BoltNative) ================================ */
  var cbs = {};
  var cseq = 0;
  var obdListener = null;
  function call(fn, args) {
    return new Promise(function (resolve, reject) {
      var id = 'c' + ++cseq;
      cbs[id] = { resolve: resolve, reject: reject };
      try { A[fn].apply(A, [id].concat(args)); } catch (e) { delete cbs[id]; reject(e); }
    });
  }
  N.cb = function (id, ok, payload) {
    var c = cbs[id];
    if (!c) return;
    delete cbs[id];
    var v = typeof payload === 'string' ? parse(payload, payload) : payload;
    if (ok) c.resolve(v);
    else c.reject(new Error(typeof v === 'string' ? v : (v && v.message) || 'Falha na ponte nativa'));
  };
  N.obdData = function (t) { if (obdListener && obdListener.onData) obdListener.onData(t); };
  N.obdClose = function (r) { if (obdListener && obdListener.onClose) obdListener.onClose(r); };
  window.BoltNative = {
    capabilities: parse(A.capabilities(), {}),
    listDevices: function (mode) { return call('obdList', [String(mode || 'spp')]); },
    connect: function (mode, address) { return call('obdConnect', [String(mode || 'spp'), String(address || '')]); },
    write: function (text) { return call('obdWrite', [String(text)]); },
    disconnect: function () { return call('obdDisconnect', []); },
    setListener: function (l) { obdListener = l || null; },
  };

  /* ============================ painel ↔ Android Auto ============================ */
  N.scroll = function (x, y, dx, dy) {
    var el = document.elementFromPoint(x, y);
    while (el && el !== document.body && el !== document.documentElement) {
      var s = getComputedStyle(el);
      var canY = /(auto|scroll)/.test(s.overflowY) && el.scrollHeight > el.clientHeight;
      var canX = /(auto|scroll)/.test(s.overflowX) && el.scrollWidth > el.clientWidth;
      if (canY || canX) { el.scrollBy(dx, dy); return; }
      el = el.parentElement;
    }
    window.scrollBy(dx, dy);
  };

  var app = null;
  N.command = function (name) {
    if (!app) return;
    try {
      if (name === 'talk') {
        var v = app.voice;
        if (v.listening && v.continuous) v.emit('wake', { command: '', transcript: '' });
        else { v.stopListening(); setTimeout(function () { v.startListening({ mode: 'tap' }); }, 150); }
      } else if (name === 'standby' && app.standby) {
        app.standby.toggle();
      }
    } catch (e) { console.error(e); }
  };

  function maybeListen() {
    try {
      var a = app;
      if (!a.assistant.enabled || !a.voice.sttSupported) return;
      if (!a.settings.get('assistant.micEnabled', false) || a.settings.get('assistant.listenMode', 'tap') !== 'continuous') return;
      if (!a.voice.listening && !a.voice.wantListening) a.voice.startListening({ mode: 'continuous' });
    } catch (e) { /* ignora */ }
  }
  N.permissionsChanged = function () { if (app) setTimeout(maybeListen, 500); };

  var waitT = setInterval(function () {
    var a = window.__alma;
    if (!a || !a.assistant || !a.voice || !a.settings || !a.alerts) return;
    clearInterval(waitT);
    app = a;
    // No navegador a escuta pelo nome só começa depois de um toque; no app ela já começa sozinha.
    setTimeout(maybeListen, 1200);
    var msgs = a.assistant.messages || [];
    var lastId = msgs.length ? msgs[msgs.length - 1].id : null;
    a.assistant.on('messages', function (list) {
      var m = list && list[list.length - 1];
      if (!m || m.id === lastId) return;
      lastId = m.id;
      if (m.role === 'assistant' && m.text) A.assistantMessage(String(m.text));
    });
    a.alerts.on('raised', function (al) {
      if (al) A.alertRaised(String(al.level || ''), String(al.title || ''), String(al.message || ''));
    });
    A.webReady();
  }, 250);
})();
