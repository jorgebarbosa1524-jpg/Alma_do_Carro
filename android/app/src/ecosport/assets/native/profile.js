/*
 * Perfil de fábrica: Ford EcoSport 2006 1.6 8V Zetec RoCam Flex, manual de 5 marchas (motorista: Jean).
 * Usado só na primeira abertura; tudo pode ser ajustado depois na Garagem e nas Configurações.
 */
window.__ALMA_PROFILE__ = {
  id: 'ecosport2006-jean',
  driverName: 'Jean',
  assistantName: 'Bolt',
  vehicle: {
    nickname: 'EcoSport',
    tech: {
      make: 'Ford',
      model: 'EcoSport',
      year: 2006,
      version: '1.6 8V Zetec RoCam Flex',
      displacementL: 1.6,
      cylinders: 4,
      fuelType: 'flex',
      aspiration: 'natural',
      transmission: 'manual',
      tankL: 45,
      redlineRpm: 6000,
      systemVoltage: 12,
      limits: { coolantWarn: 105, coolantCrit: 112, intakeWarn: 65 },
    },
    appearance: { silhouette: 'suv', color: '#B9C2CC' },
    notes: 'Motor Zetec RoCam 1.6 8V: 105 cv (gasolina) / 111 cv (etanol) a 5500 rpm, torque máximo a 4250 rpm. Tanque de 45 L.',
  },
  // Ford dessa época costuma falar J1850 PWM: o ELM327 tenta esse primeiro e, se não for, procura sozinho.
  obd: { protocolHint: '1' },
};
