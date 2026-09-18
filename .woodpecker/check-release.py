# Responsibility: require consistent semantic versions and usable release notes before accepting a CI build.
import json,os,pathlib,re,sys
root=pathlib.Path.cwd()
version=(root/'VERSION').read_text(encoding='utf-8').strip()
assert re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?',version),'VERSION must use semantic versioning'
if (root/'CMakeLists.txt').exists():
 match=re.search(r'project\([^)]*\bVERSION\s+(\d+\.\d+\.\d+)',(root/'CMakeLists.txt').read_text(encoding='utf-8'),re.I)
 assert match and match.group(1)==version,'CMake version differs from VERSION'
if (root/'package.json').exists():
 assert json.loads((root/'package.json').read_text(encoding='utf-8'))['version']==version,'package.json version differs from VERSION'
 if (root/'package-lock.json').exists():
  lock=json.loads((root/'package-lock.json').read_text(encoding='utf-8'))
  assert lock.get('version')==version and lock['packages']['']['version']==version,'package-lock.json version differs from VERSION'
changelog=(root/'CHANGELOG.md').read_text(encoding='utf-8')
section=re.search(r'^## \['+re.escape(version)+r'\](?: - ([^\n]+))?\n(.*?)(?=^## \[|\Z)',changelog,re.M|re.S)
assert section and len(section.group(2).strip())>10,'Current version requires a meaningful changelog entry'
tag=os.environ.get('CI_COMMIT_TAG','')
ref=os.environ.get('CI_COMMIT_REF','')
if ref.startswith('refs/tags/'):tag=ref.removeprefix('refs/tags/')
if tag:
 assert tag=='v'+version,'Release tag must equal v + VERSION'
 assert section.group(1) and re.fullmatch(r'\d{4}-\d{2}-\d{2}',section.group(1)),'Tagged release requires a dated changelog entry'
if '--notes' in sys.argv:print(section.group(2).strip())
else:print('Version and changelog verified:',version)
