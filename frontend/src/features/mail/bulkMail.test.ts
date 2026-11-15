import { describe, expect, it, vi } from 'vitest';
import { moveSequentially, retainVisibleSelection, toggleVisibleSelection } from './bulkMail';

describe('bulk mail primitives', () => {
  it('selects only visible mail and clears visible selection', () => {
    expect(toggleVisibleSelection(['hidden'], ['a', 'b'])).toEqual(['a', 'b']);
    expect(toggleVisibleSelection(['hidden', 'a', 'b'], ['a', 'b'])).toEqual([]);
    expect(retainVisibleSelection(['a', 'hidden'], ['a', 'b'])).toEqual(['a']);
    expect(toggleVisibleSelection([], [])).toEqual([]);
  });
  it('stops on failure and marks an uncertain outcome without retry', async () => {
    const move = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('offline'));
    const result = await moveSequentially(['a', 'b', 'c'], move);
    expect(result.succeeded).toEqual(['a']);
    expect(result.uncertain).toBe('b');
    expect(result.notAttempted).toEqual(['c']);
    expect(move.mock.calls).toEqual([['a'], ['b']]);
  });
  it('does not move duplicate ids twice', async () => {
    const move = vi.fn().mockResolvedValue(undefined);
    expect(await moveSequentially(['a', 'a', 'b'], move)).toMatchObject({ succeeded: ['a', 'b'], uncertain: null, notAttempted: [] });
    expect(move).toHaveBeenCalledTimes(2);
  });
});
