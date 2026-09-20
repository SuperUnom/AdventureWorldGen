#!/usr/bin/env python3
"""检查当前开发文档；不联网、不执行文档中的命令。只使用 Python 标准库。"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import shlex
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
LINK = re.compile(r'(?<!!)\[[^\]\n]+\]\((<[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\)')
CODE = re.compile(r'(?<!`)`([^`\n]+)`(?!`)')
CAMEL = re.compile(r'\b[A-Z][a-z]+(?:[A-Z][A-Za-z0-9]*)+\b')
HISTORY = re.compile(r'(?<![A-Za-z0-9])(?:r(?:[6-9]|[12][0-9]|3[0-3])|P[0-5])(?![A-Za-z0-9])|本轮|重构阶段|历史版本')


def prose(body: str) -> str:
    return re.sub(r'^```[^\n]*\n.*?^```\s*$', '', body, flags=re.M | re.S)


def anchors(body: str) -> set[str]:
    result = set(re.findall(r'<a\s+id=["\']([^"\']+)["\']\s*>', body))
    seen: Counter[str] = Counter()
    for heading in re.findall(r'^#{1,6}\s+(.+?)\s*#*$', prose(body), re.M):
        heading = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', heading)
        heading = re.sub(r'<[^>]*>', '', heading).lower()
        slug = re.sub(r'[^\w\s-]', '', heading).replace(' ', '-')
        suffix = '' if not seen[slug] else f'-{seen[slug]}'
        result.add(slug + suffix)
        seen[slug] += 1
    return result


def java_types() -> set[str]:
    # 项目类型包括内部 record；第三方类型取已编译源码实际使用的 import。
    types = {'FreeTerraForged', 'ReTerraForged', 'NeoForge', 'GameTest',
             'Populators', 'VolcanoPopulator', 'OutOfMemoryError', 'IllegalStateException',
             'StructurePieceType', 'ProcessResources', 'JavaExec', 'NumPy'}
    files = list((ROOT / 'neoforge/src').rglob('*.java'))
    files += list((ROOT / 'neoforge/tools').glob('*.java'))
    for path in files:
        body = path.read_text()
        types.add(path.stem)
        types.update(re.findall(r'\b(?:class|record|interface|enum)\s+(\w+)', body))
        types.update(re.findall(r'^import\s+(?:static\s+)?[\w.]+\.([A-Z]\w*)\s*;', body, re.M))
    return types


def declared_tasks() -> set[str]:
    build = (ROOT / 'neoforge/build.gradle').read_text()
    # Java plugin 标准任务 + 源码显式任务 + NeoForge run DSL 派生任务。
    tasks = {'build', 'test', 'tasks', 'clean', 'classes', 'check', 'assemble', 'jar',
             'compileJava', 'compileTestJava', 'compileTestmodJava'}
    for path in [ROOT / 'neoforge/build.gradle', *sorted((ROOT / 'neoforge/tools').glob('*.gradle'))]:
        tasks.update(re.findall(r'tasks\.(?:register|named)\([\'"]([^\'"]+)', path.read_text()))
    runs = build.split('    runs {', 1)[1].split('    addModdingDependenciesTo', 1)[0]
    tasks.update('run' + name[0].upper() + name[1:]
                 for name in re.findall(r'^        (\w+)\s*\{', runs, re.M))
    return tasks


def dependency_errors() -> list[str]:
    """概览的包依赖图必须与生产源码一致，避免把手绘关系当成第二份事实。"""
    source = ROOT / 'neoforge/src/main/java/io/github/luoyan/adventureworldgen'
    actual: set[tuple[str, str]] = set()
    for path in source.rglob('*.java'):
        package = path.relative_to(source).parts[0] if path.parent != source else 'root'
        body = re.sub(r'/\*.*?\*/|//[^\n]*', '', path.read_text(), flags=re.S)
        targets = set(re.findall(r'io\.github\.luoyan\.adventureworldgen\.([a-z]+)\.', body)) - {package}
        actual.update((package, target) for target in targets)
    overview = (ROOT / 'docs/architecture/overview.md').read_text()
    diagrams = re.findall(r'^```mermaid\s*\n(.*?)^```', overview, re.M | re.S)
    if len(diagrams) != 2:
        return ['architecture/overview.md: 预期分别提供数据流和包依赖两个 Mermaid 图']
    documented: set[tuple[str, str]] = set()
    for origin, targets in re.findall(r'^\s*(\w+)(?:\[[^\]]*\])?\s*-->\s*(.+)$', diagrams[1], re.M):
        documented.update((origin, target.strip()) for target in targets.split('&'))
    errors = [f'architecture/overview.md: 依赖图缺少 {a} → {b}' for a, b in sorted(actual - documented)]
    errors += [f'architecture/overview.md: 源码无此依赖 {a} → {b}' for a, b in sorted(documented - actual)]
    return errors


def check(gradle_log: Path | None) -> list[str]:
    docs = [ROOT / 'README.md', ROOT / 'AGENTS.md', ROOT / 'neoforge/README.md',
            ROOT / 'neoforge/tools/README.md', *sorted((ROOT / 'docs').rglob('*.md'))]
    # DOCUMENTATION_REFACTOR.md 是任务输入，保留原文，不属于开发规范。
    bodies = {path: path.read_text() for path in docs}
    types, tasks = java_types(), declared_tasks()
    if gradle_log:
        tasks = set(re.findall(r'^([A-Za-z][\w:]*)\s*(?:-.*)?$', gradle_log.read_text(), re.M))
    errors: list[str] = dependency_errors()
    link_count = command_count = 0

    def fail(path: Path, message: str) -> None:
        errors.append(f'{path.relative_to(ROOT)}: {message}')

    for path, body in bodies.items():
        for match in LINK.finditer(body):
            target = unquote(match.group(1).strip('<>'))
            url = urlsplit(target)
            if url.scheme or url.netloc:
                continue
            link_count += 1
            dest = (path.parent / url.path).resolve() if url.path else path
            if not dest.exists():
                fail(path, f'链接目标不存在: {target}')
            elif url.fragment:
                if dest.suffix != '.md' or url.fragment not in anchors(dest.read_text()):
                    fail(path, f'锚点不存在: {target}')

        plain = prose(body)
        # 路径已由链接检查；名称检查只读链接文字，避免将目录名当成类型。
        names_text = LINK.sub(lambda m: m.group(0).split('](')[0][1:], plain)
        for name in sorted(set(CAMEL.findall(names_text)) - types):
            fail(path, f'Java/技术标识未找到（项目类型或源码 import）: {name}')
        for match in HISTORY.finditer(body):
            fail(path, f'需清理或人工确认的历史措辞: {match.group(0)}')

        for block in re.findall(r'^```json\s*\n(.*?)^```', body, re.M | re.S):
            try:
                json.loads(block)
            except ValueError as error:
                fail(path, f'JSON 示例语法错误: {error}')

        snippets = CODE.findall(plain)
        snippets += re.findall(r'^```(?:bash|sh)\s*\n(.*?)^```', body, re.M | re.S)
        for snippet in snippets:
            # 源文件字面路径必须存在；生成输出和参数占位符不伪装为源码。
            if '/' in snippet and not re.search(r'\s|[<>*]', snippet):
                candidate = snippet.lstrip('./')
                generated = candidate.startswith(('neoforge/run-', 'neoforge/build/'))
                source_path = not generated and candidate.startswith(('neoforge/', 'docs/', 'src/', 'tools/'))
                if source_path and not any((base / candidate).exists() for base in (ROOT, ROOT / 'neoforge', path.parent)):
                    fail(path, f'源码/脚本路径不存在: {snippet}')
            for line in snippet.replace('\\\n', ' ').splitlines():
                if './gradlew' not in line and './neoforge/gradlew' not in line and '.sh' not in line and 'python3 ' not in line:
                    continue
                try:
                    words = shlex.split(line, comments=True)
                except ValueError as error:
                    fail(path, f'shell 语法片段错误: {error}')
                    continue
                for word in words:
                    if word.endswith(('.sh', '.py', '.gradle')) and '<' not in word:
                        if not any((base / word).exists() for base in (ROOT, ROOT / 'neoforge')):
                            fail(path, f'命令脚本不存在: {word}')
                wrapper = next((i for i, word in enumerate(words) if word.endswith('/gradlew')), None)
                if wrapper is None:
                    continue
                command_count += 1
                skip = False
                for word in words[wrapper + 1:]:
                    if skip:
                        skip = False
                        continue
                    if word in {'-I', '--init-script', '-p', '--project-dir', '--tests'}:
                        skip = True
                    elif word.startswith('-'):
                        continue
                    elif word not in tasks:
                        fail(path, f'Gradle 任务不存在: {word}')

    print(f'检查 {len(docs)} 份文档，{link_count} 个本地链接，{command_count} 个 Gradle 命令；问题 {len(errors)} 项。')
    print('生成产物/占位参数不要求预先存在；外链未联网检查；Java 检查是源码标识检查，非 API 编译验证。')
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-tasks', type=Path, help='gradlew tasks --all 的输出文件')
    args = parser.parse_args()
    errors = check(args.gradle_tasks)
    for error in errors:
        print(error, file=sys.stderr)
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
