import { expect, it } from "vitest";
import { isTaskPointer, parseWorkflowSource, taskAt, taskNamesOf } from "../../src/lib/workflow-source";

const root = parseWorkflowSource(`
use:
  extensions:
    - audit:
        extend: call
        before:
          - pause: { wait: { seconds: 1 } }
          - helper: { call: helper }
  functions:
    helper:
      do:
        - receive:
            listen: { to: { one: { with: { type: audit.ready } } } }
do:
  - invoke: { call: helper }
`).root;

it("resolves both orders of function and extension paths and hides synthetic blocks", () => {
  const functionInExtension = "/do/0/invoke/_ext/0/before/do/1/helper/_fn/do/0/receive";
  const extensionInFunction = "/do/0/invoke/_fn/do/0/receive/_ext/0/before/do/0/pause";
  expect(taskAt(root, functionInExtension)).toMatchObject({ kind: "listen", extension: { name: "audit", phase: "before", owner: "/do/0/invoke" } });
  expect(taskAt(root, extensionInFunction).kind).toBe("wait");
  expect(taskNamesOf(functionInExtension)).toEqual(["invoke", "helper", "receive"]);
  expect(isTaskPointer("/do/0/invoke/_ext/0/before")).toBe(false);
  expect(isTaskPointer("/do/0/invoke/_ext/0/before/do")).toBe(false);
  expect(isTaskPointer(functionInExtension)).toBe(true);
});

it("retains extension metadata when a remote function source is unavailable", () => {
  const remoteRoot = { ...root, do: [{ invoke: { call: "https://example.com/remote.yaml" } }] };
  expect(taskAt(remoteRoot, "/do/0/invoke/_fn/do/0/remoteOwner/_ext/0/before/do/0/pause")).toMatchObject({
    kind: "wait", extension: { name: "audit", phase: "before", owner: "/do/0/invoke/_fn/do/0/remoteOwner" },
  });
});
