"""Check authored YAML/JSON against the repository's pinned Open Workflow schema."""
import argparse
import re
import sys
from pathlib import Path

from ruamel.yaml import YAMLError

DOCS = Path(__file__).resolve().parents[4] / "lemline-docs"
sys.dont_write_bytecode = True
sys.path.insert(0, str(DOCS / "validation"))
from check_workflows import DSL_VERSION, as_specified, flow_errors, read_definition, versioned_validator

PLACEHOLDER = re.compile(r"^(?:<fill-me>|<tbd>)$", re.IGNORECASE)


def draft_markers(value, path="$"):
    if isinstance(value, dict):
        for key, item in value.items():
            yield from draft_markers(item, f"{path}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from draft_markers(item, f"{path}[{index}]")
    elif isinstance(value, str) and PLACEHOLDER.fullmatch(value.strip()):
        yield f"{path}: replace the draft marker {value!r}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workflows", type=Path, nargs="+")
    validator = versioned_validator(DOCS)
    failed = False
    for file in parser.parse_args().workflows:
        try:
            definition = read_definition(file.read_text(encoding="utf-8"), file.suffix.lower()[1:])
            errors = [f"{e.json_path}: {e.message}" for e in validator.iter_errors(as_specified(definition))]
            if not errors:
                if definition["document"]["dsl"] != DSL_VERSION:
                    errors.append(f"$.document.dsl: expected {DSL_VERSION}")
                errors.extend(flow_errors(definition["do"]))
                use = definition.get("use", {})
                for name, task in use.get("functions", {}).items():
                    errors.extend(flow_errors([{name: task}], "$.use.functions"))
                for entry in use.get("extensions", []):
                    name, extension = next(iter(entry.items()))
                    for block in ("before", "after"):
                        errors.extend(flow_errors(extension.get(block, []), f"$.use.extensions.{name}.{block}"))
                errors.extend(draft_markers(definition))
        except (OSError, ValueError, YAMLError, RecursionError) as error:
            errors = [str(error)]
        for message in errors:
            print(f"{file}: {message}", file=sys.stderr)
        failed |= bool(errors)
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
