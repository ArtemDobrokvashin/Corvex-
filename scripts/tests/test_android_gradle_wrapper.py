"""Guard: the Android project has a working Gradle wrapper.

Without `gradlew`, `gradlew.bat` and `gradle-wrapper.jar` the project cannot be
built at all: Android Studio falls back to a "no wrapper" project and CI has
nothing to call. `gradle-wrapper.properties` alone is not a wrapper — it only
names the version to download.

This test does not run Gradle (there is no JDK in CI for the mobile module).
It checks the four properties that were wrong or missing before:

1. all three wrapper files exist;
2. the jar is a real Gradle wrapper jar (it carries `GradleWrapperMain`);
3. `gradlew` is executable in git and uses LF; `gradlew.bat` uses CRLF
   (cmd.exe misreads a batch file checked out with LF endings);
4. the distribution URL matches the toolchain the Android module targets.
"""
from pathlib import Path
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / 'android'
GRADLEW = ANDROID / 'gradlew'
GRADLEW_BAT = ANDROID / 'gradlew.bat'
WRAPPER_JAR = ANDROID / 'gradle' / 'wrapper' / 'gradle-wrapper.jar'
WRAPPER_PROPS = ANDROID / 'gradle' / 'wrapper' / 'gradle-wrapper.properties'


class GradleWrapperTest(unittest.TestCase):
    def test_wrapper_files_exist(self) -> None:
        for path in (GRADLEW, GRADLEW_BAT, WRAPPER_JAR):
            self.assertTrue(path.is_file(), f'{path.relative_to(ROOT)} отсутствует')

    def test_jar_is_a_gradle_wrapper(self) -> None:
        self.assertTrue(zipfile.is_zipfile(WRAPPER_JAR), 'gradle-wrapper.jar не является jar')
        with zipfile.ZipFile(WRAPPER_JAR) as jar:
            names = jar.namelist()
        self.assertIn(
            'org/gradle/wrapper/GradleWrapperMain.class', names,
            'в jar нет GradleWrapperMain: это не wrapper, а что-то другое',
        )

    def test_line_endings_match_the_platform(self) -> None:
        # LF в sh-скрипте: с CRLF `#!/bin/sh\r` даёт «bad interpreter» на Linux и macOS.
        self.assertNotIn(b'\r\n', GRADLEW.read_bytes(), 'gradlew должен быть с LF')
        # CRLF в .bat: cmd.exe читает файл построчно и ломается на LF-переводах.
        self.assertIn(b'\r\n', GRADLEW_BAT.read_bytes(), 'gradlew.bat должен быть с CRLF')

    def test_distribution_matches_the_android_toolchain(self) -> None:
        props = WRAPPER_PROPS.read_text(encoding='utf-8')
        self.assertIn('gradle-8.10.2-bin.zip', props)
        # AGP 8.7 требует Gradle 8.9+, поэтому «просто какая-нибудь» версия не подойдёт.
        build = (ANDROID / 'build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('com.android.application', build)
        self.assertIn('8.7.2', build)


if __name__ == '__main__':
    unittest.main()
