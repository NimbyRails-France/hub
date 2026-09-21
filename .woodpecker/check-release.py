# Validate release channels, version fields and changelog before a build can pass.
import json,os,pathlib,re,sys
root=pathlib.Path.cwd()
policy=json.loads((root/'release-channels.json').read_text(encoding='utf-8'))
version=(root/'VERSION').read_text(encoding='utf-8').strip()
match=re.fullmatch(r'((?:0|[1-9][0-9]{0,3})\.(?:0|[1-9][0-9]{0,3})\.(?:0|[1-9][0-9]{0,3}))(?:-(alpha|beta|dev)\.([1-9][0-9]{0,8}))?',version)
assert match,'Use X.Y.Z or X.Y.Z-channel.N (N starts at 1)'
channel=match.group(2) or 'stable'
assert channel in policy['channels'],'Channel is not enabled for this project'
if (root/'CMakeLists.txt').exists():
 cmake=re.search(r'project\([^)]*\bVERSION\s+(\d+\.\d+\.\d+)',(root/'CMakeLists.txt').read_text(encoding='utf-8'),re.I)
 assert cmake and cmake.group(1)==match.group(1),'CMake must match the numeric part of VERSION'
if (root/'package.json').exists():
 assert json.loads((root/'package.json').read_text(encoding='utf-8'))['version']==version,'package.json differs from VERSION'
 if (root/'package-lock.json').exists():
  lock=json.loads((root/'package-lock.json').read_text(encoding='utf-8'))
  assert lock.get('version')==version and lock['packages']['']['version']==version,'package-lock.json differs from VERSION'
if (root/'build.gradle.kts').exists():
 gradle=(root/'build.gradle.kts').read_text(encoding='utf-8')
 assert 'file("VERSION").readText().trim()' in gradle,'Gradle must read VERSION'
 runtime=(root/'src/commonMain/kotlin/fr/nimby/hub/model/Projects.kt').read_text(encoding='utf-8')
 assert f'const val HUB_VERSION = "{version}"' in runtime,'Runtime version differs from VERSION'
changelog=(root/'CHANGELOG.md').read_text(encoding='utf-8')
section=re.search(r'^## \['+re.escape(version)+r'\](?: - ([^\n]+))?\n(.*?)(?=^## \[|\Z)',changelog,re.M|re.S)
assert section and len(section.group(2).strip())>10,'Current version needs a meaningful changelog entry'
tag=os.environ.get('CI_COMMIT_TAG','')
ref=os.environ.get('CI_COMMIT_REF','')
if ref.startswith('refs/tags/'):tag=ref.removeprefix('refs/tags/')
if tag:
 assert tag=='v'+version,'Release tag must equal v + VERSION'
 assert section.group(1) and re.fullmatch(r'\d{4}-\d{2}-\d{2}',section.group(1)),'Tagged releases need a dated changelog'
if '--notes' in sys.argv:print(section.group(2).strip())
else:print('Version, channel and changelog verified:',version,channel)

# Decide whether this commit is eligible for publication.
if "--notes" not in sys.argv:
 import runpy
 runpy.run_path(str(root/".woodpecker/release-policy.py"),run_name="__main__")
