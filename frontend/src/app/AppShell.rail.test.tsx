// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const shell = readFileSync(resolve(__dirname, 'AppShell.tsx'), 'utf8');
const styles = readFileSync(resolve(__dirname, '../index.css'), 'utf8');

describe('desktop rail matches the reference', () => {
  it('uses circular icon buttons with the label kept accessible', () => {
    expect(shell).toContain('className="rail-btn relative"');
    expect(shell).toContain('aria-label={label}');
    expect(shell).toContain('title={label}');
    // No visible text label inside the rail item anymore.
    expect(shell).not.toContain('<span>{label}</span>');
    expect(shell).toContain('<CalendarWidget compact />');
  });

  it('keeps the mobile navigation labelled', () => {
    expect(shell).toContain('{label}\n    </NavLink>');
  });

  it('inverts the active circle in both themes', () => {
    expect(styles).toContain('.rail-btn[aria-current="page"]');
    expect(styles).toContain('--corvex-rail-active: #14151A;');
    expect(styles).toContain('--corvex-rail-active: #F2F2F0;');
    expect(styles).toContain('--corvex-rail-item: #1A1B1E;');
  });
});
