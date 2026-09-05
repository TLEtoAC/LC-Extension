/**
 * tabLifecycle.test.js — Jest (Phase 11)
 * cycleInProgress persistence + recoverMissingTabs.
 */

import { jest } from '@jest/globals';

const store = {
  tabIds: { Q1: 11, Q2: null, Q3: 33, Q4: 44 },
  cycleInProgress: false,
  discoveredQuestions: [
    { questionNumber: 'Q1', problemUrl: 'https://leetcode.com/problems/a' },
    { questionNumber: 'Q2', problemUrl: 'https://leetcode.com/problems/b' },
    { questionNumber: 'Q3', problemUrl: 'https://leetcode.com/problems/c' },
    { questionNumber: 'Q4', problemUrl: 'https://leetcode.com/problems/d' },
  ],
};

const liveTabs = new Map([[11, {}], [33, {}], [44, {}]]);
let nextTabId = 100;

global.chrome = {
  storage: {
    local: {
      get: jest.fn(async (key) => {
        if (typeof key === 'string') {
          return { [key]: store[key] };
        }
        const out = {};
        for (const k of key) {
          out[k] = store[k];
        }
        return out;
      }),
      set: jest.fn(async (obj) => {
        Object.assign(store, obj);
      }),
    },
  },
  tabs: {
    get: jest.fn(async (id) => {
      if (!liveTabs.has(id)) {
        throw new Error('No tab');
      }
      return { id };
    }),
    create: jest.fn(async ({ url }) => {
      const id = nextTabId++;
      liveTabs.set(id, { url });
      return { id, url };
    }),
    onUpdated: {
      addListener: jest.fn(),
      removeListener: jest.fn(),
    },
  },
};

const {
  getCycleInProgress,
  setCycleInProgress,
  recoverMissingTabs,
  getPersistedTabIds,
} = await import('../background/tabLifecycleManager.js');

describe('cycleInProgress', () => {
  test('persists through storage, not module memory', async () => {
    await setCycleInProgress(true);
    expect(await getCycleInProgress()).toBe(true);
    await setCycleInProgress(false);
    expect(await getCycleInProgress()).toBe(false);
  });
});

describe('recoverMissingTabs', () => {
  test('reopens a null slot and persists the new tab id', async () => {
    const result = await recoverMissingTabs('https://leetcode.com/contest/weekly-400/');
    expect(result.recovered).toEqual(['Q2']);
    expect(result.tabIds.Q2).toBeGreaterThan(0);
    const persisted = await getPersistedTabIds();
    expect(persisted.Q2).toBe(result.tabIds.Q2);
    expect(chrome.tabs.create).toHaveBeenCalledWith({
      url: 'https://leetcode.com/problems/b',
      active: false,
    });
  });
});
