/**
 * Perfil de fábrica opcional. O app Android de cada carro (ex.: a versão EcoSport) define
 * `window.__ALMA_PROFILE__` antes do app.js. Sem perfil (navegador, versão Renegade), nada muda.
 *
 * Formato:
 *   { id, driverName?, assistantName?, vehicle: { nickname, tech: {...}, appearance: {...} },
 *     obd?: { protocolHint?: '1'..'C' } }
 */
export function factoryProfile(env = globalThis) {
  const p = env && env.__ALMA_PROFILE__;
  if (!p || typeof p !== 'object' || !p.id || !p.vehicle) return null;
  return p;
}

/** Opções da sessão OBD vindas do perfil (ex.: J1850 PWM primeiro nos Ford antigos). */
export function obdSessionOptions(profile) {
  const hint = profile?.obd?.protocolHint;
  return typeof hint === 'string' && /^[1-9A-C]$/i.test(hint) ? { protocolHint: hint.toUpperCase() } : {};
}
