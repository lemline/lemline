import tempfile
import unittest
from pathlib import Path

from check_workflows import check_docs


WORKFLOW = """document:
  dsl: '1.0.3'
  namespace: examples
  name: hello
  version: '1.0.0'
do:
  - greet:
      set:
        message: Hello
"""
INCLUDE = '<code-block lang="yaml" src="workflows/hello.yaml"/>'


class WorkflowCheckTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.project = Path(directory.name)
        self.schema = Path(__file__).with_name("workflow-1.0.3.json")
        self.write("validation/workflow-1.0.3.json", self.schema.read_text(encoding="utf-8"))

    def write(self, name, content):
        path = self.project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def check(self, topic, definition=WORKFLOW):
        self.write("topics/example.md", topic)
        if definition is not None:
            self.write("snippets/workflows/hello.yaml", definition)
        return check_docs(self.project)

    def test_accepts_an_included_workflow_and_an_inline_configuration(self):
        counts, errors = self.check(
            f'{INCLUDE}\n\n<!-- workflow-example: data -->\n```yaml\nlemline:\n  database: sqlite\n```\n'
        )
        self.assertEqual({"workflows": 1, "counterexamples": 0}, counts)
        self.assertEqual([], errors)

    def test_accepts_script_arguments_as_the_map_lemline_reads(self):
        script = WORKFLOW.replace(
            "      set:\n        message: Hello\n",
            "      run:\n        script:\n          language: js\n          code: console.log('hi')\n"
            "          arguments:\n            name: ${ .name }\n",
        )
        _, errors = self.check(INCLUDE, script)
        self.assertEqual([], errors)

    def test_rejects_a_workflow_bypassing_its_source(self):
        _, errors = self.check(f"{INCLUDE}\n\n```yaml\n{WORKFLOW}```\n")
        self.assertTrue(any("topics/example.md:3:" in error for error in errors))

    def test_known_workflow_cannot_be_labeled_as_data(self):
        _, errors = self.check(f"{INCLUDE}\n\n<!-- workflow-example: data -->\n```yaml\n{WORKFLOW}```\n")
        self.assertTrue(any("topics/example.md:4:" in error for error in errors))

    def test_requires_classification_for_new_inline_yaml(self):
        _, errors = self.check(f"{INCLUDE}\n\n```yaml\nvalue: true\n```\n")
        self.assertTrue(any("classify inline configuration" in error for error in errors))

    def test_rejects_an_invalid_task_and_reports_its_location(self):
        _, errors = self.check(INCLUDE, WORKFLOW.replace("set:", "unrecognized:"))
        self.assertTrue(any("hello.yaml: $.do[0].greet:" in error for error in errors))

    def test_rejects_a_missing_source(self):
        _, errors = self.check(INCLUDE, None)
        self.assertTrue(any("missing workflow source" in error for error in errors))

    def test_rejects_unreferenced_symlink_without_reading_its_target(self):
        self.write("outside.yaml", "outside-value-that-must-not-appear")
        self.write("snippets/workflows/hello.yaml", WORKFLOW)
        (self.project / "snippets/workflows/outside.yaml").symlink_to(self.project / "outside.yaml")
        _, errors = self.check(INCLUDE)
        self.assertEqual(["snippets/workflows/outside.yaml: symbolic link is not supported"], errors)

    def test_requires_named_destinations_in_complete_examples(self):
        definition = WORKFLOW + "      then: unrelatedTask\n"
        _, errors = self.check(INCLUDE, definition)
        self.assertTrue(any("$.do.greet.then: no task named 'unrelatedTask'" in error for error in errors))
        _, errors = self.check(INCLUDE.replace("/>", ' include-lines="6-9"/>'), definition)
        self.assertEqual([], errors)

    def test_rejects_inline_content_on_a_sourced_block(self):
        _, errors = self.check(INCLUDE.replace("/>", ">different content</code-block>"))
        self.assertTrue(any("self-closing code-block" in error for error in errors))

    def test_rejects_out_of_bounds_line_ranges(self):
        _, errors = self.check(INCLUDE.replace("/>", ' include-lines="1-100"/>'))
        self.assertTrue(any("invalid include-lines" in error for error in errors))

    def test_counterexample_must_be_labeled_and_invalid(self):
        self.write("snippets/counterexamples/bad.yaml", "document: {}\n")
        negative = '<code-block lang="yaml" src="counterexamples/bad.yaml"/>'
        _, errors = self.check(f"{INCLUDE}\n\n{negative}\n")
        self.assertTrue(any("introduce the counterexample" in error for error in errors))
        _, errors = self.check(f"{INCLUDE}\n\n**Invalid workflow example.**\n\n{negative}\n")
        self.assertEqual([], errors)
        self.write("snippets/counterexamples/bad.yaml", WORKFLOW)
        _, errors = self.check(f"{INCLUDE}\n\n**Invalid workflow example.**\n\n{negative}\n")
        self.assertTrue(any("counterexample unexpectedly satisfies" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
