/**
 * alarmEnded.test.js — Jest (Phase 11)
 * ENDED observation uses the Phase 7 handleContestEnded hook only.
 */

import { jest } from '@jest/globals';

const store = {
  monitoringStopped: false,
  cycleInProgress: false,
  monitoringIntervalMinutes: 5,
  tabIds: { Q1: 1, Q2: 2, Q3: 3, Q4: 4 },
};

global.chrome = {
  storage: {
    local: {
      get: jest.fn(async (key) => {
        if (typeof key === 'string') {
          return { [key]: store[key] };
        }
        const out = {};
        for (const k of Array.isArray(key) ? key : Object.keys(store)) {
          out[k] = store[k];
        }
        return out;
      }),
      set: jest.fn(async (obj) => {
        Object.assign(store, obj);
      }),
    },
  },
  alarms: {
    clear: jest.fn(async () => true),
    create: jest.fn(async () => {}),
    get: jest.fn(async () => null),
  },
};

global.fetch = jest.fn();

const { observeEndedAndStop, isMonitoringStopped } = await import('../background/alarmScheduler.js');

describe('observeEndedAndStop', () => {
  beforeEach(() => {
    store.monitoringStopped = false;
  });

  test('does not stop when lifecycle is MONITORING', async () => {
    global.fetch.mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({ initialized: true, lifecycleState: 'MONITORING' }),
    });
    const ended = await observeEndedAndStop('test');
    expect(ended).toBe(false);
    expect(await isMonitoringStopped()).toBe(false);
  });

  test('calls handleContestEnded when status reports ENDED', async () => {
    global.fetch.mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({ initialized: true, lifecycleState: 'ENDED' }),
    });
    const ended = await observeEndedAndStop('test');
    expect(ended).toBe(true);
    expect(await isMonitoringStopped()).toBe(true);
    expect(chrome.alarms.clear).toHaveBeenCalledWith('scrapeCycle');
  });
});
