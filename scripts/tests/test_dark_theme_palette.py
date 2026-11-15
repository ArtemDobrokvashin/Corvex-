"""Palette regression only; browser rendering and all components require visual testing."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CSS = (ROOT / 'frontend/src/index.css').read_text(encoding='utf-8')
STORE = (ROOT / 'frontend/src/store/uiStore.ts').read_text(encoding='utf-8')
TAILWIND = (ROOT / 'frontend/tailwind.config.js').read_text(encoding='utf-8')


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
        # Тёмный сайдбар задан фиксированными токенами Tailwind и не зависит от темы,
        # поэтому читаемость считается по ним, а не по удалённому .mail-sidebar.
        palette = TAILWIND.split("sidebar: {", 1)[1].split("},", 1)[0]
        tokens = dict(re.findall(r"(\w+):\s*'(#[\da-fA-F]{6})'", palette))
        self.assertGreaterEqual(contrast(tokens['text'], tokens['DEFAULT']), 4.5)
        self.assertGreaterEqual(contrast(tokens['muted'], tokens['DEFAULT']), 4.5)
        self.assertGreaterEqual(contrast(tokens['muted'], tokens['sub']), 4.5)
        self.assertGreaterEqual(contrast('#FFFFFF', tokens['active']), 4.5)
        self.assertGreaterEqual(contrast('#FFFFFF', tokens['sub']), 4.5)
        # Активная папка отличается не только цветом: полоска слева у элемента списка.
        self.assertIn('.sidebar-item-active::before', CSS)
        self.assertIn('w-[3px] rounded-r bg-accent', CSS)
        shell = (ROOT / 'frontend/src/app/AppShell.tsx').read_text(encoding='utf-8')
        self.assertIn('bg-sidebar text-sidebar-text', shell)
        page = (ROOT / 'frontend/src/features/mail/MailPage.tsx').read_text(encoding='utf-8')
        self.assertIn('sidebar-subitem w-full', page)
        self.assertIn('sidebar-subitem-active', page)
        self.assertIn("aria-current={isCurrent ? 'page' : undefined}", page)
        # Старые правила для снятой раскладки не должны возвращаться.
        self.assertNotIn('.mail-sidebar', CSS)

    def test_selected_component_has_background_and_non_color_indicator(self):
        self.assertIn('[data-theme="dark"] .nav-row-active:hover', CSS)
        self.assertIn('box-shadow: inset 3px 0 var(--corvex-accent-on-dark)', CSS)
        self.assertIn('background-color: var(--corvex-accent-soft)', CSS)


if __name__ == '__main__':
    unittest.main()
