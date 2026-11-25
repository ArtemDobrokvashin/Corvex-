// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { menuPosition } from './MailPage';

/**
 * Меню «Ещё действия» открывалось, но было обрезано тулбаром: тулбар
 * прокручивается по горизонтали, а `absolute`-меню внутри него не выходит за
 * границы. Поэтому меню рендерится в портале, и его положение считается здесь.
 *
 * Тест держит три свойства, из-за которых это ломалось:
 *   1. меню не выезжает за правый край окна;
 *   2. у правого края тулбара оно выравнивается по правой стороне кнопки;
 *   3. если внизу мало места, меню открывается вверх, а не за экран.
 */
describe('menuPosition', () => {
  const viewport = { width: 1000, height: 700 };

  function anchor(partial: Partial<DOMRect>): DOMRect {
    return {
      x: 0, y: 0, width: 24, height: 24,
      top: 0, left: 0, right: 24, bottom: 24,
      toJSON: () => ({}),
      ...partial,
    } as DOMRect;
  }

  function withViewport(w: number, h: number, run: () => void) {
    const original = { w: window.innerWidth, h: window.innerHeight };
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: w });
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: h });
    try {
      run();
    } finally {
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: original.w });
      Object.defineProperty(window, 'innerHeight', { configurable: true, value: original.h });
    }
  }

  it('keeps the menu inside the window when the button sits at the right edge', () => {
    withViewport(viewport.width, viewport.height, () => {
      const { left } = menuPosition(anchor({ left: 960, right: 984, top: 10, bottom: 34 }), 240);

      expect(left).toBeGreaterThanOrEqual(8);
      expect(left + 240).toBeLessThanOrEqual(viewport.width - 8);
    });
  });

  it('aligns the menu to the right edge of the button', () => {
    withViewport(viewport.width, viewport.height, () => {
      const { left } = menuPosition(anchor({ left: 400, right: 424, top: 10, bottom: 34 }), 240);

      expect(left).toBe(424 - 240);
    });
  });

  it('opens below the button when there is room', () => {
    withViewport(viewport.width, viewport.height, () => {
      const { top } = menuPosition(anchor({ top: 10, bottom: 34 }), 240, 200);

      expect(top).toBe(38);
    });
  });

  it('flips above the button when the bottom would be cut off', () => {
    withViewport(600, 400, () => {
      const { top } = menuPosition(anchor({ top: 340, bottom: 364 }), 240, 300);

      expect(top).toBe(340 - 300 - 4);
      expect(top).toBeGreaterThanOrEqual(8);
    });
  });

  it('never returns a negative offset on a narrow window', () => {
    withViewport(320, 480, () => {
      const { left, top } = menuPosition(anchor({ left: 300, right: 320, top: 10, bottom: 34 }), 240);

      // 320 - 240 - 8 = 72: правая граница меню остаётся в окне, левая — не отрицательная.
      expect(left).toBe(72);
      expect(top).toBe(38);
    });
  });

  it('clamps to the left margin when the window is narrower than the menu', () => {
    withViewport(200, 480, () => {
      const { left } = menuPosition(anchor({ left: 180, right: 200, top: 10, bottom: 34 }), 240);

      // Меню шире окна: держимся за левое поле, чтобы не уехать в минус.
      expect(left).toBe(8);
    });
  });
});
