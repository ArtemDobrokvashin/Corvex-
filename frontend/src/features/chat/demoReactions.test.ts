import { describe, expect, it } from 'vitest';
import {
  createInitialDemoReactionState,
  demoReactionKey,
  toggleDemoReaction,
} from './demoReactions';

describe('demo chat reactions', () => {
  it('adds exactly one to an existing reaction count', () => {
    const initial = createInitialDemoReactionState();

    const next = toggleDemoReaction(initial, 'c1', '👍');

    expect(next.counts.c1['👍']).toBe(3);
    expect(next.reactedKeys.has(demoReactionKey('c1', '👍'))).toBe(true);
    expect(initial.counts.c1['👍']).toBe(2);
  });

  it('is replay-safe when React invokes an updater again with the same previous state', () => {
    const initial = createInitialDemoReactionState();

    const first = toggleDemoReaction(initial, 'c1', '👍');
    const replay = toggleDemoReaction(initial, 'c1', '👍');

    expect(replay.counts).toEqual(first.counts);
    expect(replay.counts.c1['👍']).toBe(3);
  });

  it('supports different emoji independently and toggles a reaction off', () => {
    const withHeart = toggleDemoReaction(createInitialDemoReactionState(), 'c2', '❤️');
    const withoutHeart = toggleDemoReaction(withHeart, 'c2', '❤️');

    expect(withHeart.counts.c2['❤️']).toBe(1);
    expect(withHeart.counts.c1['👍']).toBe(2);
    expect(withoutHeart.counts.c2).toBeUndefined();
    expect(withoutHeart.reactedKeys.has(demoReactionKey('c2', '❤️'))).toBe(false);
  });
});
