"""Check that Writerside workflows are sourced from valid Open Workflow documents."""

import json
import re
import sys
from pathlib import Path, PurePosixPath
from xml.etree import ElementTree

from jsonschema import Draft202012Validator
from markdown_it import MarkdownIt
from ruamel.yaml import YAML, YAMLError

DSL_VERSION = "1.0.0"
LANGUAGES = {"yaml", "yml", "json"}
SUFFIXES = {f".{language}" for language in LANGUAGES}
TASK_KEYS = {
    "call", "do", "emit", "for", "fork", "listen", "raise", "run", "set", "switch", "try", "wait",
}
LEGACY_CALL_KEYS = {"callHTTP", "callGRPC", "callOpenAPI", "callAsyncAPI"}
WORKFLOW_KEYS = {
    "authentications", "catch", "do", "evaluate", "retry", "schedule", "specVersion", "use",
}
LEGACY_TASK_TYPES = TASK_KEYS | {
    "action", "callback", "delay", "event", "inject", "operation", "parallel", "sleep", "subflow", "while",
}
DOCUMENT_KEYS = {"dsl", "namespace", "name", "version"}
CODE_BLOCK = re.compile(r"<code-block\b[^>]*>")
INLINE_DATA = "<!-- workflow-example: data -->"
COUNTEREXAMPLE = "**Invalid workflow example.**"
INCLUDE_WORKFLOW = "include workflow examples with <code-block src=.../>"
FLOW_DIRECTIVES = {"continue", "exit", "end"}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate key {key!r}")
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError(f"invalid JSON constant {value}")


def read_definition(source, language):
    if language == "json":
        return json.loads(source, object_pairs_hook=unique_object, parse_constant=reject_constant)
    return YAML(typ="safe", pure=True).load(source)


def is_task(value):
    if not isinstance(value, dict):
        return False
    if value.get("name") and isinstance(value.get("type"), str) and value["type"] in LEGACY_TASK_TYPES:
        return True
    return bool((TASK_KEYS | LEGACY_CALL_KEYS).intersection(value))


def looks_like_workflow(source, language):
    try:
        value = read_definition(source, language)
    except (ValueError, YAMLError):
        # Keep catching recognizable workflows when an edit breaks their YAML or JSON.
        return bool(re.search(
            r'(?m)^\s*(?:-\s+)?["\']?(?:document|dsl|do|states|specVersion|workflowId)["\']?\s*:'
            r'|^\s*-\s+name\s*:[\s\S]*?\n\s*type\s*:\s*["\']?(?:'
            + "|".join(sorted(LEGACY_TASK_TYPES)) + r')\b',
            source,
        ))
    if isinstance(value, dict):
        document = value.get("document")
        return (
            bool(WORKFLOW_KEYS.intersection(value))
            or (isinstance(document, dict) and bool(DOCUMENT_KEYS.intersection(document)))
            or ("id" in value and ("functions" in value or "states" in value or "tasks" in value))
            or (
                isinstance(value.get("functions"), list)
                and any(
                    isinstance(item, dict) and "name" in item
                    and {"operation", "service", "specification", "type"}.intersection(item)
                    for item in value["functions"]
                )
            )
            or (
                isinstance(value.get("tasks"), list)
                and any(is_task(item) for item in value["tasks"])
            )
            or any(
                isinstance(value.get(key), dict)
                and {"schema", "from" if key == "input" else "as"}.intersection(value[key])
                for key in ("input", "output", "export")
            )
            or is_task(value)
        )
    if isinstance(value, list):
        return any(
            is_task(item) or (
                isinstance(item, dict) and len(item) == 1 and is_task(next(iter(item.values())))
            )
            for item in value
        )
    return False


def versioned_validator(project):
    schema_path = project / "validation" / f"workflow-{DSL_VERSION}.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def flow_errors(tasks, path="$.do"):
    names = {next(iter(entry)) for entry in tasks}
    for entry in tasks:
        name, task = next(iter(entry.items()))
        task_path = f"{path}.{name}"
        directives = [(f"{task_path}.then", task.get("then"))]
        for case in task.get("switch", []):
            label, branch = next(iter(case.items()))
            directives.append((f"{task_path}.switch.{label}.then", branch.get("then")))
        if "catch" in task:
            directives.append((f"{task_path}.catch.then", task["catch"].get("then")))
        for location, target in directives:
            if target is not None and target not in FLOW_DIRECTIVES and target not in names:
                yield f"{location}: no task named {target!r} in this scope"

        for key in ("do", "try"):
            if key in task:
                yield from flow_errors(task[key], f"{task_path}.{key}")
        for key in ("catch", "foreach", "fork"):
            child_key = "branches" if key == "fork" else "do"
            if key in task and child_key in task[key]:
                yield from flow_errors(task[key][child_key], f"{task_path}.{key}.{child_key}")


def definition_errors(source, language, validator, *, complete=False):
    try:
        definition = read_definition(source, language)
    except (ValueError, YAMLError) as error:
        return [str(error)]
    errors = [
        f"{error.json_path}: {error.message}"
        for error in validator.iter_errors(definition)
    ]
    if isinstance(definition, dict):
        document = definition.get("document")
        if isinstance(document, dict) and "dsl" in document and document["dsl"] != DSL_VERSION:
            errors.append(f"$.document.dsl: expected {DSL_VERSION!r}, got {document['dsl']!r}")
    if complete and not errors:
        errors.extend(flow_errors(definition["do"]))
    return errors


def valid_line_ranges(ranges, line_count):
    for part in ranges.split(","):
        if not re.fullmatch(r"[1-9]\d*(?:-[1-9]\d*)?", part.strip()):
            return False
        start, _, end = part.partition("-")
        if not 1 <= int(start) <= int(end or start) <= line_count:
            return False
    return True


def check_inline(source, language, preceding_line):
    if looks_like_workflow(source, language):
        return INCLUDE_WORKFLOW
    if preceding_line.strip() != INLINE_DATA:
        return f"classify inline configuration or sample data with {INLINE_DATA}, or {INCLUDE_WORKFLOW}"
    return None


def code_block_tags(token):
    if token.type == "html_block":
        yield from CODE_BLOCK.finditer(token.content)
    elif token.type == "inline":
        offset = 0
        for child in token.children:
            if child.type != "html_inline":
                continue
            start = token.content.find(child.content, offset)
            if start < 0:
                continue
            offset = start + len(child.content)
            block = CODE_BLOCK.match(token.content, start)
            if block:
                yield block


def topic_references(topic, snippets, complete):
    content = topic.read_text(encoding="utf-8")
    lines = content.splitlines()
    for token in MarkdownIt().parse(content):
        if token.type == "fence":
            language, *_ = token.info.lower().split() or [""]
            start = token.map[0]
            if language in LANGUAGES:
                error = check_inline(token.content, language, lines[start - 1] if start else "")
                if error:
                    yield start + 1, None, error
            elif not language and looks_like_workflow(token.content, "yaml"):
                yield start + 1, None, INCLUDE_WORKFLOW
        for block in code_block_tags(token):
            index = token.map[0] + token.content.count("\n", 0, block.start())
            attributes = block.group().removesuffix(">").removesuffix("/")
            try:
                element = ElementTree.fromstring(attributes + "/>")
            except ElementTree.ParseError as error:
                yield index + 1, None, f"invalid code-block attributes: {error}"
                continue
            source = element.get("src", "")
            language = element.get("lang", "").lower()
            path = PurePosixPath(source)
            if not source:
                body = token.content[block.end():].split("</code-block>", 1)[0]
                if language in LANGUAGES:
                    error = check_inline(body, language, lines[index - 1] if index else "")
                    if error:
                        yield index + 1, None, error
                continue
            if language not in LANGUAGES and path.suffix not in SUFFIXES:
                continue
            if not block.group().endswith("/>"):
                yield index + 1, None, "use a self-closing code-block for a workflow source"
                continue
            if path.parts[:1] not in {("workflows",), ("counterexamples",)} or (
                ".." in path.parts or path.suffix not in SUFFIXES
            ):
                yield index + 1, None, "use a YAML or JSON source under snippets/workflows or snippets/counterexamples"
                continue
            file = snippets / path
            if any(candidate.is_symlink() for candidate in (file, *file.parents) if candidate.is_relative_to(snippets)):
                yield index + 1, None, f"symbolic link in workflow source {source}"
                continue
            if not file.is_file():
                yield index + 1, None, f"missing workflow source {source}"
                continue
            if path.parts[0] == "counterexamples" and (
                index < 2 or lines[index - 2].strip() != COUNTEREXAMPLE or lines[index - 1].strip()
            ):
                yield index + 1, None, f"introduce the counterexample with {COUNTEREXAMPLE}"
            if element.get("include-symbol") is not None:
                yield index + 1, None, "use include-lines for workflow excerpts"
            ranges = element.get("include-lines")
            if ranges is not None and not valid_line_ranges(ranges, len(file.read_text(encoding="utf-8").splitlines())):
                yield index + 1, None, f"invalid include-lines range {ranges!r} for {source}"
            if ranges is None:
                complete.add(file)
            yield index + 1, file, None


def check_docs(project):
    validator = versioned_validator(project)
    snippets = project / "snippets"
    errors = []
    referenced = set()
    complete = set()
    if snippets.is_symlink():
        return {}, ["snippets: symbolic link is not supported"]
    for topic in sorted((project / "topics").rglob("*.md")):
        for line, file, error in topic_references(topic, snippets, complete):
            if error is None:
                referenced.add(file)
            else:
                errors.append(f"{topic.relative_to(project)}:{line}: {error}")

    counts = {}
    for directory in ("workflows", "counterexamples"):
        root = snippets / directory
        counts[directory] = 0
        if root.is_symlink():
            errors.append(f"{root.relative_to(project)}: symbolic link is not supported")
            continue
        for file in sorted(root.rglob("*")):
            name = file.relative_to(project)
            if file.is_symlink():
                errors.append(f"{name}: symbolic link is not supported")
                continue
            if not file.is_file() or file.suffix not in SUFFIXES:
                continue
            counts[directory] += 1
            if file not in referenced:
                errors.append(f"{name}: workflow source is not included in any topic")
            failures = definition_errors(
                file.read_text(encoding="utf-8"), file.suffix[1:], validator,
                complete=directory == "workflows" and file in complete,
            )
            if directory == "workflows":
                errors.extend(f"{name}: {failure}" for failure in failures)
            elif not failures:
                errors.append(f"{name}: counterexample unexpectedly satisfies the schema")
    if not counts["workflows"]:
        errors.append("no workflow sources found")
    return counts, errors


def main():
    project = Path(__file__).resolve().parents[1]
    counts, errors = check_docs(project)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(
        f"Open Workflow {DSL_VERSION}: {counts['workflows']} workflows validated, "
        f"{counts['counterexamples']} counterexamples rejected."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
