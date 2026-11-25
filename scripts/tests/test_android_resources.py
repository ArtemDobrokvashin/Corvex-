"""Guard: the Android app declares the resources its manifest points at.

The mobile client has no Android job in CI, so a manifest that references a
missing resource — or an app with no launcher icon at all — reaches `main`
while all three CI jobs stay green. That is exactly what happened before:
the app had no `res/values` at all, no icon, and a label hard-coded in the
manifest.

This test does not compile Kotlin and does not run aapt. It checks the
property that broke: every `@type/name` in the manifest resolves to something
declared under `app/src/main/res`, and the launcher activity exists.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'android' / 'app' / 'src' / 'main'
RES = APP / 'res'
MANIFEST = APP / 'AndroidManifest.xml'

ANDROID_NS = '{http://schemas.android.com/apk/res/android}'
# `@string/app_name`, `@style/Theme.Corvex`, but not `@android:style/...`.
LOCAL_REF = re.compile(r'@(?!android:)(\w+)/([\w.]+)')


def _resource_texts() -> dict[Path, str]:
    return {p: p.read_text(encoding='utf-8') for p in sorted(RES.rglob('*.xml'))}


def _declared(kind: str, name: str, texts: dict[Path, str]) -> bool:
    for path, text in texts.items():
        if path.name == f'{name}.xml':
            return True
        pattern = (
            r'<(string|color|style|drawable|mipmap|bool|integer|dimen)'
            r'[^>]*name="' + re.escape(name) + r'"'
        )
        if re.search(pattern, text):
            return True
    return False


class AndroidResourcesGuardTest(unittest.TestCase):

    def test_manifest_exists_and_parses(self):
        self.assertTrue(MANIFEST.is_file(), 'AndroidManifest.xml отсутствует')
        ET.parse(MANIFEST)

    def test_every_manifest_resource_reference_is_declared(self):
        texts = _resource_texts()
        self.assertTrue(texts, 'под app/src/main/res нет ни одного ресурса')
        manifest = MANIFEST.read_text(encoding='utf-8')

        missing = [
            f'@{kind}/{name}'
            for kind, name in LOCAL_REF.findall(manifest)
            if not _declared(kind, name, texts)
        ]
        self.assertEqual([], missing, f'манифест ссылается на отсутствующие ресурсы: {missing}')

    def test_app_has_a_launcher_icon_and_a_name(self):
        texts = _resource_texts()
        # Иконка и имя — то, без чего приложение выглядит как «без имени» в лончере.
        for kind, name in (('mipmap', 'ic_launcher'), ('string', 'app_name')):
            self.assertTrue(
                _declared(kind, name, texts),
                f'нет ресурса {kind}/{name} — приложение установится без иконки или имени',
            )

    def test_launcher_activity_is_exported(self):
        root = ET.parse(MANIFEST).getroot()
        exported = [
            activity.get(ANDROID_NS + 'name')
            for activity in root.iter('activity')
            if activity.get(ANDROID_NS + 'exported') == 'true'
        ]
        self.assertTrue(exported, 'ни одна activity не exported — приложение не запустится из лончера')

    def test_window_theme_is_declared(self):
        texts = _resource_texts()
        manifest = MANIFEST.read_text(encoding='utf-8')
        theme = re.search(r'android:theme="@style/([\w.]+)"', manifest)
        self.assertIsNotNone(theme, 'у приложения нет собственной темы окна')
        self.assertTrue(
            _declared('style', theme.group(1), texts),
            f'тема {theme.group(1)} объявлена в манифесте, но не в res/values',
        )


if __name__ == '__main__':
    unittest.main()
