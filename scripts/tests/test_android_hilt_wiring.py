"""Hilt wiring guard for the Android client.

`AuthRepository` and `MailRepository` take Retrofit interfaces as constructor
parameters. Retrofit interfaces have no `@Inject` constructor, so Hilt cannot
build them on its own: without an explicit `@Provides` the graph fails with
`MissingBinding`. The failure only shows up in an Android build, and this
repository has no Android job in CI — so a broken graph once sat in `main`
while all three CI jobs were green.

This test does not compile Kotlin. It checks the property that actually broke:
for every injected Retrofit API there is a provider that creates it. A comment
or an import is not a provider.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / 'android' / 'app' / 'src' / 'main' / 'java'
DI_MODULES = ANDROID / 'com' / 'corvex' / 'mobile' / 'di'
API_DIR = ANDROID / 'com' / 'corvex' / 'mobile' / 'data' / 'network' / 'api'

# `private val authApi: AuthApi,` inside a constructor.
INJECTED_API = re.compile(r'private\s+val\s+\w+\s*:\s*([A-Z]\w*Api)\b')


def _files(suffix: str) -> list[Path]:
    return sorted(p for p in ANDROID.rglob(f'*{suffix}') if p.is_file()) if ANDROID.is_dir() else []


def _read(path: Path) -> str:
    return path.read_text(encoding='utf-8')


def _trimmed_lines(text: str):
    for line in text.splitlines():
        stripped = line.strip()
        # Comments describe intent; they are not bindings.
        if not stripped or stripped.startswith('//') or stripped.startswith('*') or stripped.startswith('/*'):
            continue
        yield stripped


def injected_apis() -> set[str]:
    found: set[str] = set()
    for path in _files('.kt'):
        for line in _trimmed_lines(_read(path)):
            found.update(INJECTED_API.findall(line))
    return found


def provided_apis() -> set[str]:
    """APIs that some DI module actually creates, not merely imports."""
    sources = {p: _read(p) for p in _files('.kt') if DI_MODULES in p.parents}
    created: set[str] = set()

    # `fun provideX(...): AuthApi = retrofit.create(AuthApi::class.java)`
    for text in sources.values():
        for match in re.finditer(r'\)\s*:\s*([A-Z]\w*Api)\s*=\s*([^@]+?)(?=@Provides|\Z)', text, re.S):
            api, body = match.group(1), match.group(2)
            if 'create' in body:
                created.add(api)

    # `@Binds abstract fun bindX(impl: XImpl): AuthApi`
    for text in sources.values():
        created.update(re.findall(r'@Binds[^)]*?\)\s*:\s*([A-Z]\w*Api)\b', text, re.S))
    return created


# ViewModels get their dependencies through the Hilt graph too. A service the
# ViewModel injects is built by Hilt (the `Service` suffix is the project's
# naming rule), so a new service without `@Inject constructor` compiles fine
# and then fails at runtime with MissingBinding -- exactly what the API tests
# above catch one layer up.
VIEWMODEL_CTOR = re.compile(r'class\s+\w*ViewModel\b[\s\S]{0,600}?constructor\s*\(([\s\S]*?)\)\s*:')


def injected_services() -> set[str]:
    found: set[str] = set()
    for path in _files('ViewModel.kt'):
        for match in VIEWMODEL_CTOR.finditer(_read(path)):
            found.update(re.findall(r':\s*([A-Z]\w*Service)\b', match.group(1)))
    return found


def hilt_provided_types() -> set[str]:
    """Types Hilt can build itself: `@Inject constructor` or an explicit provider."""
    provided: set[str] = set()
    for path in _files('.kt'):
        text = _read(path)
        provided.update(re.findall(r'class\s+([A-Z]\w*)\b[\s\S]{0,400}?@Inject\s+constructor', text))
        provided.update(re.findall(r'class\s+([A-Z]\w*)\b[\s\S]{0,400}?constructor\s*\([^)]*@Inject', text))
        provided.update(re.findall(r'\)\s*:\s*([A-Z]\w*Service)\s*=', text))
    return provided


class AndroidHiltWiringTest(unittest.TestCase):
    def test_android_sources_are_present(self):
        if not DI_MODULES.is_dir():
            self.skipTest('android client is not in this checkout')
        self.assertTrue(API_DIR.is_dir(), f'missing API package: {API_DIR}')

    def test_every_injected_retrofit_api_has_a_provider(self):
        if not DI_MODULES.is_dir():
            self.skipTest('android client is not in this checkout')
        missing = sorted(injected_apis() - provided_apis())
        self.assertEqual([], missing,
                         'Repositories inject Retrofit APIs that no @Provides/@Binds creates; '
                         'Hilt will fail with MissingBinding. Add retrofit.create(...) in a DI module.')

    def test_providers_are_not_merely_imported(self):
        """The failure this guards against: imports added, providers forgotten."""
        if not DI_MODULES.is_dir():
            self.skipTest('android client is not in this checkout')
        network = DI_MODULES / 'NetworkModule.kt'
        text = _read(network)
        imported = set(re.findall(r'^import\s+\S+\.([A-Z]\w*Api)$', text, re.M))
        created = provided_apis()
        self.assertEqual(set(), imported - created,
                         'These APIs are imported in NetworkModule but never created; '
                         'the import alone does not satisfy Hilt.')


    def test_services_injected_into_viewmodels_are_provided(self):
        """The failure this guards against: `private val mail: MailService` with no @Inject."""
        if not ANDROID.is_dir():
            self.skipTest('android client is not in this checkout')
        missing = sorted(injected_services() - hilt_provided_types())
        self.assertEqual([], missing,
                         'ViewModels inject services that the Hilt graph cannot build; '
                         'Hilt will fail with MissingBinding. Add @Inject constructor to the '
                         'service or a @Provides that creates it.')
