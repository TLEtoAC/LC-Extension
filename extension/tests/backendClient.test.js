/**
 * backendClient.test.js — Jest (Phase 11)
 * Covers slot normalize, drop-on-unreachable, and refused-connection handling.
 */

import { jest } from '@jest/globals';

const storage = { backendUnreachable: false };

global.chrome = {
  storage: {
    local: {
      get: jest.fn(async (key) => {
        if (typeof key === 'string') {
          return { [key]: storage[key] };
        }
        return { ...storage };
      }),
      set: jest.fn(async (obj) => {
        Object.assign(storage, obj);
      }),
    },
  },
};

const { normalizeQuestionSlot, postIngest, getStatus } = await import('../background/backendClient.js');

describe('normalizeQuestionSlot', () => {
  test('accepts 1, "1", "q1", "Q1"', () => {
    expect(normalizeQuestionSlot(1)).toBe('Q1');
    expect(normalizeQuestionSlot('1')).toBe('Q1');
    expect(normalizeQuestionSlot('q1')).toBe('Q1');
    expect(normalizeQuestionSlot('Q1')).toBe('Q1');
  });
});

describe('postIngest', () => {
  beforeEach(() => {
    storage.backendUnreachable = false;
    global.fetch = jest.fn();
  });

  test('drops immediately when backendUnreachable is true', async () => {
    storage.backendUnreachable = true;
    const result = await postIngest('Q1', { scrapingStatus: 'SUCCESS', rawUsersAccepted: '1 / 2' });
    expect(result).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  test('refused connection sets backendUnreachable and returns null', async () => {
    global.fetch.mockRejectedValue(new TypeError('Failed to fetch'));
    const result = await postIngest(1, { scrapingStatus: 'SUCCESS', rawUsersAccepted: '1 / 2' });
    expect(result).toBeNull();
    expect(storage.backendUnreachable).toBe(true);
  });
});

describe('getStatus', () => {
  test('returns parsed JSON on 200', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({ initialized: false, questions: [] }),
    });
    storage.backendUnreachable = true;
    const result = await getStatus();
    expect(result).toEqual({ initialized: false, questions: [] });
    expect(storage.backendUnreachable).toBe(false);
  });
});
