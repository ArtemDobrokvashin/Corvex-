"""Palette regression only; browser rendering and all components require visual testing."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CSS = (ROOT / 'frontend/src/index.css').read_text(encoding='utf-8')
STORE = (ROOT / 'frontend/src/store/uiStore.ts').read_text(encoding='utf-8')


def luminance(color):
    channels = [int(color[i:i+2], 16) / 255 for i in (1, 3, 5)]
    linear = [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in channels]
    return sum(a * b for a, b in zip(linear, (0.2126, 0.7152, 0.0722)))


def contrast(a, b):
    lo, hi = sorted((luminance(a), luminance(b)))
    return (hi + 0.05) / (lo + 0.05)


class DarkPaletteTest(unittest.TestCase):
    def test_palette_and_all_accent_text_on_selected_background(self):
        block = CSS.split('[data-theme="dark"] {', 1)[1].split('}', 1)[0]
        tokens = dict(re.findall(r'--corvex-([\w-]+):\s*(#[\da-fA-F]{6})\s*;', block))
        self.assertIn('color-scheme: dark', block)
        for text in ('ink', 'ink-soft', 'muted', 'faint'):
            self.assertGreaterEqual(contrast(tokens[text], tokens['surface']), 4.5, text)
        accents = STORE.split('const ACCENT_ON_DARK:', 1)[1].split('};', 1)[0]
        colors = re.findall(r'#[\da-fA-F]{6}', accents)
        self.assertEqual(len(colors), 6)
        for color in colors:
            self.assertGreaterEqual(contrast(color, tokens['accent-soft']), 4.5, color)

    def test_mail_sidebar_readability_and_scope(self):
        for text, background in (('#a9bad0', '#0f172a'), ('#cbd5e1', '#0f172a'), ('#8bf0c2', '#183d37')):
            self.assertGreaterEqual(contrast(text, background), 4.5)
        sidebar = CSS.split('/* Dark navigation is confined', 1)[1]
        self.assertIn('.mail-sidebar .nav-row-active:hover', sidebar)
        self.assertIn('box-shadow: inset 3px 0 #8bf0c2', sidebar)
        page = (ROOT / 'frontend/src/features/mail/MailPage.tsx').read_text(encoding='utf-8')
        self.assertIn('<aside className="mail-sidebar ', page)

    def test_selected_component_has_background_and_non_color_indicator(self):
        self.assertIn('[data-theme="dark"] .nav-row-active:hover', CSS)
        self.assertIn('box-shadow: inset 3px 0 var(--corvex-accent-on-dark)', CSS)
        self.assertIn('background-color: var(--corvex-accent-soft)', CSS)


if __name__ == '__main__':
    unittest.main()
