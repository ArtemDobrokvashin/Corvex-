import { describe, expect, it, vi } from 'vitest';
import { moveSequentially, retainVisibleSelection, toggleVisibleSelection } from './bulkMail';

describe('bulk mail primitives', () => {
  it('selects only visible mail and clears visible selection', () => {
    expect(toggleVisibleSelection(['hidden'], ['a', 'b'])).toEqual(['a', 'b']);
    expect(toggleVisibleSelection(['hidden', 'a', 'b'], ['a', 'b'])).toEqual([]);
    expect(retainVisibleSelection(['a', 'hidden'], ['a', 'b'])).toEqual(['a']);
    expect(toggleVisibleSelection([], [])).toEqual([]);
  });
  it('stops on an unknown outcome and marks it uncertain without retry', async () => {
    const move = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('offline'));
    const result = await moveSequentially(['a', 'b', 'c'], move);
    expect(result.succeeded).toEqual(['a']);
    expect(result.uncertain).toEqual(['b']);
    expect(result.notAttempted).toEqual(['c']);
    expect(result.rejected).toEqual([]);
    expect(move.mock.calls).toEqual([['a'], ['b']]);
  });
  it('does not move duplicate ids twice', async () => {
    const move = vi.fn().mockResolvedValue(undefined);
    expect(await moveSequentially(['a', 'a', 'b'], move)).toMatchObject({ succeeded: ['a', 'b'], uncertain: [], notAttempted: [] });
    expect(move).toHaveBeenCalledTimes(2);
  });
  it('continues after an explicit server refusal instead of stopping the batch', async () => {
    const refused = Object.assign(new Error('forbidden'), { status: 403 });
    const move = vi.fn()
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(refused)
      .mockResolvedValueOnce(undefined);
    const result = await moveSequentially(['a', 'b', 'c'], move, (error) => (error as { status?: number }).status === 403);
    expect(result.succeeded).toEqual(['a', 'c']);
    expect(result.rejected).toEqual(['b']);
    expect(result.uncertain).toEqual([]);
    expect(result.notAttempted).toEqual([]);
    expect(result.error).toBe(refused);
  });
});
