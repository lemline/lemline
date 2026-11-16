#!/usr/bin/env python3
"""
Validate Serverless Workflow v1.0.0 documents for one-shot authoring quality.

Profiles:
- spec-strict
- lemline-compatible
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import Any

TASK_TYPES = {
    "call",
    "do",
    "fork",
    "emit",
    "for",
    "listen",
    "raise",
    "run",
    "set",
    "switch",
    "try",
    "wait",
}
FLOW_DIRECTIVES = {"continue", "exit", "end"}
PROTOCOL_CALLS = {"http", "openapi", "grpc", "asyncapi"}
PLACEHOLDER_PATTERNS = (
    re.compile(r"\bTODO\b", re.IGNORECASE),
    re.compile(r"<\s*fill", re.IGNORECASE),
    re.compile(r"<\s*tbd\s*>", re.IGNORECASE),
)


@dataclass
class Issue:
    severity: str
    path: str
    message: str


def add_error(issues: list[Issue], path: str, message: str) -> None:
    issues.append(Issue("ERROR", path, message))


def add_warning(issues: list[Issue], path: str, message: str) -> None:
    issues.append(Issue("WARN", path, message))


def load_yaml(path: str) -> Any:
    try:
        import yaml  # type: ignore

        with open(path, "r", encoding="utf-8") as f:
            return yaml.safe_load(f)
    except ModuleNotFoundError:
        cmd = [
            "ruby",
            "-ryaml",
            "-rjson",
            "-e",
            "data = YAML.safe_load(File.read(ARGV[0]), aliases: true); puts(JSON.generate(data))",
            path,
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "Ruby YAML parser failed")
        return json.loads(result.stdout)


def ensure_dict(value: Any, issues: list[Issue], path: str, label: str) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        add_error(issues, path, f"{label} must be an object")
        return None
    return value


def ensure_list(value: Any, issues: list[Issue], path: str, label: str) -> list[Any] | None:
    if not isinstance(value, list):
        add_error(issues, path, f"{label} must be an array")
        return None
    return value


def has_prop(obj: dict[str, Any], key: str) -> bool:
    if key in obj:
        return True
    if key == "on" and True in obj:  # YAML 1.1 compatibility for unquoted "on"
        return True
    if key == "on" and "true" in obj:  # Ruby YAML->JSON compatibility
        return True
    if key == "off" and False in obj:  # YAML 1.1 compatibility for unquoted "off"
        return True
    if key == "off" and "false" in obj:  # Ruby YAML->JSON compatibility
        return True
    return False


def get_prop(obj: dict[str, Any], key: str) -> Any:
    if key in obj:
        return obj.get(key)
    if key == "on" and True in obj:
        return obj.get(True)
    if key == "on" and "true" in obj:
        return obj.get("true")
    if key == "off" and False in obj:
        return obj.get(False)
    if key == "off" and "false" in obj:
        return obj.get("false")
    return None


def is_duration_value(value: Any) -> bool:
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, dict):
        keys = {"days", "hours", "minutes", "seconds", "milliseconds"}
        if not value:
            return False
        unknown = set(value.keys()) - keys
        if unknown:
            return False
        return all(isinstance(v, int) for v in value.values())
    return False


def validate_duration(value: Any, issues: list[Issue], path: str, label: str) -> None:
    if not is_duration_value(value):
        add_error(issues, path, f"{label} must be an inline duration object or ISO-8601 duration string")


def validate_event_filter(value: Any, issues: list[Issue], path: str) -> None:
    event_filter = ensure_dict(value, issues, path, "event filter")
    if event_filter is None:
        return
    with_obj = event_filter.get("with")
    with_map = ensure_dict(with_obj, issues, f"{path}/with", "event filter with")
    if with_map is not None and not with_map:
        add_error(issues, f"{path}/with", "event filter with must define at least one attribute")
    correlate = event_filter.get("correlate")
    if correlate is None:
        return
    correlate_map = ensure_dict(correlate, issues, f"{path}/correlate", "event correlations")
    if correlate_map is None:
        return
    for key, corr in correlate_map.items():
        corr_obj = ensure_dict(corr, issues, f"{path}/correlate/{key}", "correlation")
        if corr_obj is not None and "from" not in corr_obj:
            add_error(issues, f"{path}/correlate/{key}/from", "correlation.from is required")


def validate_event_consumption_strategy(value: Any, issues: list[Issue], path: str, allow_until: bool = True) -> None:
    strategy = ensure_dict(value, issues, path, "event consumption strategy")
    if strategy is None:
        return
    modes = [k for k in ("all", "any", "one") if k in strategy]
    if len(modes) != 1:
        add_error(issues, path, "event consumption strategy must define exactly one of all, any, one")
        return
    mode = modes[0]
    if "until" in strategy and mode != "any":
        add_error(issues, f"{path}/until", "until is only allowed when strategy mode is any")
    if not allow_until and "until" in strategy:
        add_error(issues, f"{path}/until", "nested until is not allowed")

    if mode in {"all", "any"}:
        filters = ensure_list(strategy.get(mode), issues, f"{path}/{mode}", f"{mode} event filters")
        if filters is None:
            return
        for idx, item in enumerate(filters):
            validate_event_filter(item, issues, f"{path}/{mode}/{idx}")
    else:
        validate_event_filter(strategy.get("one"), issues, f"{path}/one")

    until_value = strategy.get("until")
    if until_value is None:
        return
    if isinstance(until_value, str):
        if not until_value.strip():
            add_error(issues, f"{path}/until", "until expression cannot be empty")
    elif isinstance(until_value, dict):
        validate_event_consumption_strategy(until_value, issues, f"{path}/until", allow_until=False)
    else:
        add_error(issues, f"{path}/until", "until must be an expression string or event consumption strategy")


def validate_retry_policy(value: Any, issues: list[Issue], path: str) -> None:
    retry = ensure_dict(value, issues, path, "retry policy")
    if retry is None:
        return
    limit = retry.get("limit")
    bounded = False
    if isinstance(limit, dict):
        attempt = limit.get("attempt")
        if isinstance(attempt, dict):
            if isinstance(attempt.get("count"), int):
                bounded = True
            if "duration" in attempt:
                validate_duration(attempt.get("duration"), issues, f"{path}/limit/attempt/duration", "retry attempt.duration")
                bounded = True
        if "duration" in limit:
            validate_duration(limit.get("duration"), issues, f"{path}/limit/duration", "retry limit.duration")
            bounded = True
    if not bounded:
        add_warning(issues, path, "retry policy appears unbounded (no attempt/duration limit found)")

    jitter = retry.get("jitter")
    if isinstance(jitter, dict):
        if "from" not in jitter or "to" not in jitter:
            add_error(issues, f"{path}/jitter", "jitter requires both from and to")
        else:
            validate_duration(jitter.get("from"), issues, f"{path}/jitter/from", "jitter.from")
            validate_duration(jitter.get("to"), issues, f"{path}/jitter/to", "jitter.to")


def validate_timeout_value(value: Any, timeout_names: set[str], issues: list[Issue], path: str) -> None:
    if isinstance(value, str):
        if value not in timeout_names:
            add_error(issues, path, f"timeout reference '{value}' not found in use.timeouts")
        return
    timeout_obj = ensure_dict(value, issues, path, "timeout")
    if timeout_obj is None:
        return
    if "after" not in timeout_obj:
        add_error(issues, f"{path}/after", "timeout.after is required")
        return
    validate_duration(timeout_obj.get("after"), issues, f"{path}/after", "timeout.after")


def walk_for_placeholders(node: Any, issues: list[Issue], path: str) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            walk_for_placeholders(value, issues, f"{path}/{key}")
        return
    if isinstance(node, list):
        for idx, value in enumerate(node):
            walk_for_placeholders(value, issues, f"{path}/{idx}")
        return
    if isinstance(node, str):
        trimmed = node.strip()
        if trimmed in {"...", "<fill-me>", "<todo>", "<tbd>"}:
            add_error(issues, path, "placeholder value is not allowed in one-shot output")
            return
        for pattern in PLACEHOLDER_PATTERNS:
            if pattern.search(node):
                add_error(issues, path, "placeholder marker is not allowed in one-shot output")
                return


def validate_then(then_value: Any, scope_names: set[str], issues: list[Issue], path: str) -> None:
    if not isinstance(then_value, str):
        add_error(issues, path, "then must be a string flow directive")
        return
    if then_value in FLOW_DIRECTIVES:
        return
    if then_value not in scope_names:
        add_error(
            issues,
            path,
            f"then target '{then_value}' is outside the current scope or missing",
        )


def validate_auth_ref(
    auth_obj: Any,
    auth_names: set[str],
    issues: list[Issue],
    path: str,
) -> None:
    if not isinstance(auth_obj, dict):
        return
    ref = auth_obj.get("use")
    if isinstance(ref, str) and ref not in auth_names:
        add_error(issues, path, f"authentication.use references unknown key '{ref}'")


def validate_call(
    task: dict[str, Any],
    scope_names: set[str],
    timeout_names: set[str],
    retry_names: set[str],
    error_names: set[str],
    auth_names: set[str],
    issues: list[Issue],
    path: str,
    profile: str,
) -> None:
    del scope_names, timeout_names, retry_names, error_names
    call_value = task.get("call")
    with_obj = task.get("with")

    if not isinstance(call_value, str):
        add_error(issues, f"{path}/call", "call must be a string")
        return

    if call_value in PROTOCOL_CALLS and not isinstance(with_obj, dict):
        add_error(issues, f"{path}/with", f"with is required for protocol call '{call_value}'")
        return

    if profile == "lemline-compatible" and call_value in {"openapi", "grpc", "asyncapi"}:
        add_error(issues, f"{path}/call", f"'{call_value}' is not supported in lemline-compatible profile")

    if call_value == "http":
        if not isinstance(with_obj, dict):
            return
        if "method" not in with_obj:
            add_error(issues, f"{path}/with/method", "http call requires with.method")
        if "endpoint" not in with_obj:
            add_error(issues, f"{path}/with/endpoint", "http call requires with.endpoint")
        endpoint = with_obj.get("endpoint")
        if isinstance(endpoint, dict):
            validate_auth_ref(endpoint.get("authentication"), auth_names, issues, f"{path}/with/endpoint/authentication")
    elif call_value == "openapi":
        if "document" not in with_obj:
            add_error(issues, f"{path}/with/document", "openapi call requires with.document")
        if "operationId" not in with_obj:
            add_error(issues, f"{path}/with/operationId", "openapi call requires with.operationId")
        validate_auth_ref(with_obj.get("authentication"), auth_names, issues, f"{path}/with/authentication")
    elif call_value == "grpc":
        proto = with_obj.get("proto")
        service = with_obj.get("service")
        if proto is None:
            add_error(issues, f"{path}/with/proto", "grpc call requires with.proto")
        if not isinstance(service, dict):
            add_error(issues, f"{path}/with/service", "grpc call requires with.service")
        else:
            if "name" not in service:
                add_error(issues, f"{path}/with/service/name", "grpc call requires with.service.name")
            if "host" not in service:
                add_error(issues, f"{path}/with/service/host", "grpc call requires with.service.host")
            validate_auth_ref(service.get("authentication"), auth_names, issues, f"{path}/with/service/authentication")
        if "method" not in with_obj:
            add_error(issues, f"{path}/with/method", "grpc call requires with.method")
    elif call_value == "asyncapi":
        if "document" not in with_obj:
            add_error(issues, f"{path}/with/document", "asyncapi call requires with.document")
        has_operation = "operation" in with_obj
        has_channel = "channel" in with_obj
        if not has_operation and not has_channel:
            add_error(issues, f"{path}/with", "asyncapi call requires with.operation or with.channel")
        has_message = "message" in with_obj
        has_subscription = "subscription" in with_obj
        if has_message == has_subscription:
            add_error(
                issues,
                f"{path}/with",
                "asyncapi call requires exactly one of with.message or with.subscription",
            )
        validate_auth_ref(with_obj.get("authentication"), auth_names, issues, f"{path}/with/authentication")
        sub = with_obj.get("subscription")
        if isinstance(sub, dict):
            consume = sub.get("consume")
            consume_obj = ensure_dict(consume, issues, f"{path}/with/subscription/consume", "asyncapi subscription.consume")
            if consume_obj is not None:
                consume_modes = [k for k in ("amount", "while", "until") if k in consume_obj]
                if len(consume_modes) != 1:
                    add_error(
                        issues,
                        f"{path}/with/subscription/consume",
                        "subscription.consume must define exactly one of amount, while, until",
                    )
                if "for" in consume_obj:
                    validate_duration(
                        consume_obj.get("for"),
                        issues,
                        f"{path}/with/subscription/consume/for",
                        "subscription.consume.for",
                    )
            foreach = sub.get("foreach")
            if isinstance(foreach, dict) and "do" in foreach:
                validate_task_list(
                    foreach.get("do"),
                    f"{path}/with/subscription/foreach/do",
                    issues,
                    profile,
                    timeout_names,
                    retry_names,
                    error_names,
                    auth_names,
                )


def validate_task(
    task_name: str,
    task_body: Any,
    scope_names: set[str],
    issues: list[Issue],
    path: str,
    profile: str,
    timeout_names: set[str],
    retry_names: set[str],
    error_names: set[str],
    auth_names: set[str],
) -> None:
    _ = task_name
    task = ensure_dict(task_body, issues, path, "task body")
    if task is None:
        return

    if "then" in task:
        validate_then(task.get("then"), scope_names, issues, f"{path}/then")

    if "timeout" in task:
        validate_timeout_value(task.get("timeout"), timeout_names, issues, f"{path}/timeout")

    present = [k for k in TASK_TYPES if k in task]
    if not present:
        add_error(issues, path, "task must define one task type key")
        return
    if "for" in present:
        disallowed = [k for k in present if k not in {"for", "do"}]
        if disallowed:
            add_error(issues, path, "for task cannot be combined with other task type keys")
            return
        task_type = "for"
    else:
        if len(present) != 1:
            add_error(issues, path, "task must define exactly one task type key")
            return
        task_type = present[0]

    if task_type == "call":
        validate_call(
            task,
            scope_names,
            timeout_names,
            retry_names,
            error_names,
            auth_names,
            issues,
            path,
            profile,
        )

    elif task_type == "do":
        validate_task_list(
            task.get("do"),
            f"{path}/do",
            issues,
            profile,
            timeout_names,
            retry_names,
            error_names,
            auth_names,
        )

    elif task_type == "for":
        loop_cfg = ensure_dict(task.get("for"), issues, f"{path}/for", "for configuration")
        if loop_cfg is not None and "in" not in loop_cfg:
            add_error(issues, f"{path}/for/in", "for.in is required")
        validate_task_list(
            task.get("do"),
            f"{path}/do",
            issues,
            profile,
            timeout_names,
            retry_names,
            error_names,
            auth_names,
        )

    elif task_type == "fork":
        fork_cfg = ensure_dict(task.get("fork"), issues, f"{path}/fork", "fork configuration")
        if fork_cfg is None:
            return
        branches = ensure_list(fork_cfg.get("branches"), issues, f"{path}/fork/branches", "fork.branches")
        if branches is None:
            return
        if not branches:
            add_error(issues, f"{path}/fork/branches", "fork.branches cannot be empty")
            return
        for idx, branch in enumerate(branches):
            if isinstance(branch, dict):
                add_error(
                    issues,
                    f"{path}/fork/branches/{idx}",
                    "each fork branch must be an array of task items (wrap the branch object in a list)",
                )
                continue
            validate_task_list(
                branch,
                f"{path}/fork/branches/{idx}",
                issues,
                profile,
                timeout_names,
                retry_names,
                error_names,
                auth_names,
            )

    elif task_type == "emit":
        emit_cfg = ensure_dict(task.get("emit"), issues, f"{path}/emit", "emit configuration")
        if emit_cfg is None:
            return
        event_cfg = ensure_dict(emit_cfg.get("event"), issues, f"{path}/emit/event", "emit.event")
        if event_cfg is None:
            return
        event_with = event_cfg.get("with")
        if isinstance(event_with, dict):
            if "source" not in event_with:
                add_error(issues, f"{path}/emit/event/with/source", "emit.event.with.source is required")
            if "type" not in event_with:
                add_error(issues, f"{path}/emit/event/with/type", "emit.event.with.type is required")

    elif task_type == "listen":
        listen_cfg = ensure_dict(task.get("listen"), issues, f"{path}/listen", "listen configuration")
        if listen_cfg is not None:
            if "to" not in listen_cfg:
                add_error(issues, f"{path}/listen/to", "listen.to is required")
            else:
                validate_event_consumption_strategy(listen_cfg.get("to"), issues, f"{path}/listen/to")
        foreach_cfg = task.get("foreach")
        if isinstance(foreach_cfg, dict) and "do" in foreach_cfg:
            validate_task_list(
                foreach_cfg.get("do"),
                f"{path}/foreach/do",
                issues,
                profile,
                timeout_names,
                retry_names,
                error_names,
                auth_names,
            )

    elif task_type == "raise":
        raise_cfg = ensure_dict(task.get("raise"), issues, f"{path}/raise", "raise configuration")
        if raise_cfg is None:
            return
        err = raise_cfg.get("error")
        if err is None:
            add_error(issues, f"{path}/raise/error", "raise.error is required")
        elif isinstance(err, str):
            if profile == "lemline-compatible":
                add_error(
                    issues,
                    f"{path}/raise/error",
                    "named raise.error references are not supported in lemline-compatible profile",
                )
            elif err not in error_names:
                add_error(issues, f"{path}/raise/error", f"raise.error reference '{err}' not found in use.errors")

    elif task_type == "run":
        run_cfg = ensure_dict(task.get("run"), issues, f"{path}/run", "run configuration")
        if run_cfg is None:
            return
        modes = [k for k in ("container", "script", "shell", "workflow") if k in run_cfg]
        if len(modes) != 1:
            add_error(issues, f"{path}/run", "run must define exactly one of container, script, shell, workflow")
        if profile == "lemline-compatible" and "container" in modes:
            add_error(issues, f"{path}/run/container", "run.container is not supported in lemline-compatible profile")
        if "script" in modes:
            script = ensure_dict(run_cfg.get("script"), issues, f"{path}/run/script", "run.script")
            if script is not None:
                language = script.get("language")
                if not isinstance(language, str):
                    add_error(issues, f"{path}/run/script/language", "run.script.language is required")
                elif language.lower() not in {"js", "python"}:
                    add_error(
                        issues,
                        f"{path}/run/script/language",
                        "run.script.language must be 'js' or 'python'",
                    )
                has_code = "code" in script
                has_source = "source" in script
                if has_code == has_source:
                    add_error(issues, f"{path}/run/script", "run.script must define exactly one of code or source")
        if "workflow" in modes:
            workflow_cfg = ensure_dict(run_cfg.get("workflow"), issues, f"{path}/run/workflow", "run.workflow")
            if workflow_cfg is not None:
                for required_key in ("namespace", "name", "version"):
                    if required_key not in workflow_cfg:
                        add_error(
                            issues,
                            f"{path}/run/workflow/{required_key}",
                            f"run.workflow.{required_key} is required",
                        )
        if "container" in modes:
            container = ensure_dict(run_cfg.get("container"), issues, f"{path}/run/container", "run.container")
            if container is not None:
                lifetime = container.get("lifetime")
                if isinstance(lifetime, dict):
                    cleanup = lifetime.get("cleanup")
                    if cleanup == "eventually" and "after" not in lifetime:
                        add_error(
                            issues,
                            f"{path}/run/container/lifetime/after",
                            "container lifetime cleanup 'eventually' requires after",
                        )
                    if "after" in lifetime:
                        validate_duration(
                            lifetime.get("after"),
                            issues,
                            f"{path}/run/container/lifetime/after",
                            "container lifetime.after",
                        )

    elif task_type == "set":
        set_cfg = task.get("set")
        if not isinstance(set_cfg, dict) or not set_cfg:
            add_error(issues, f"{path}/set", "set must be a non-empty object")

    elif task_type == "switch":
        cases = ensure_list(task.get("switch"), issues, f"{path}/switch", "switch cases")
        if cases is None:
            return
        if not cases:
            add_error(issues, f"{path}/switch", "switch must have at least one case")
            return
        default_count = 0
        for idx, case_item in enumerate(cases):
            if not isinstance(case_item, dict) or len(case_item) != 1:
                add_error(issues, f"{path}/switch/{idx}", "switch case must be single-key map")
                continue
            case_name, case_body = next(iter(case_item.items()))
            _ = case_name
            case_obj = ensure_dict(case_body, issues, f"{path}/switch/{idx}", "switch case definition")
            if case_obj is None:
                continue
            if "then" not in case_obj:
                add_error(issues, f"{path}/switch/{idx}/then", "switch case requires then")
            else:
                validate_then(case_obj.get("then"), scope_names, issues, f"{path}/switch/{idx}/then")
            if "when" not in case_obj:
                default_count += 1
        if default_count > 1:
            add_error(issues, f"{path}/switch", "switch can define at most one default case")

    elif task_type == "try":
        validate_task_list(
            task.get("try"),
            f"{path}/try",
            issues,
            profile,
            timeout_names,
            retry_names,
            error_names,
            auth_names,
        )
        catch_cfg = ensure_dict(task.get("catch"), issues, f"{path}/catch", "catch configuration")
        if catch_cfg is None:
            return
        retry_ref = catch_cfg.get("retry")
        if isinstance(retry_ref, str) and retry_ref not in retry_names:
            add_error(issues, f"{path}/catch/retry", f"retry reference '{retry_ref}' not found in use.retries")
        if isinstance(retry_ref, dict):
            validate_retry_policy(retry_ref, issues, f"{path}/catch/retry")
        if "do" in catch_cfg:
            validate_task_list(
                catch_cfg.get("do"),
                f"{path}/catch/do",
                issues,
                profile,
                timeout_names,
                retry_names,
                error_names,
                auth_names,
            )

    elif task_type == "wait":
        if "wait" not in task:
            add_error(issues, f"{path}/wait", "wait is required")
        else:
            validate_duration(task.get("wait"), issues, f"{path}/wait", "wait")


def validate_task_list(
    task_list_value: Any,
    path: str,
    issues: list[Issue],
    profile: str,
    timeout_names: set[str],
    retry_names: set[str],
    error_names: set[str],
    auth_names: set[str],
) -> None:
    task_list = ensure_list(task_list_value, issues, path, "task list")
    if task_list is None:
        return
    if not task_list:
        add_warning(issues, path, "task list is empty")
        return

    scope_names: list[str] = []
    seen_names: set[str] = set()
    for idx, item in enumerate(task_list):
        if not isinstance(item, dict) or len(item) != 1:
            add_error(issues, f"{path}/{idx}", "task item must be a single-key map")
            continue
        name = next(iter(item.keys()))
        if not isinstance(name, str) or not name:
            add_error(issues, f"{path}/{idx}", "task name must be a non-empty string")
            continue
        if name in seen_names:
            add_error(issues, f"{path}/{idx}", f"duplicate task name '{name}' in same scope")
        seen_names.add(name)
        scope_names.append(name)

    scope_name_set = set(scope_names)
    for idx, item in enumerate(task_list):
        if not isinstance(item, dict) or len(item) != 1:
            continue
        name, task_body = next(iter(item.items()))
        validate_task(
            name,
            task_body,
            scope_name_set,
            issues,
            f"{path}/{idx}/{name}",
            profile,
            timeout_names,
            retry_names,
            error_names,
            auth_names,
        )


def walk_for_grants(node: Any, issues: list[Issue], path: str, profile: str) -> None:
    if isinstance(node, dict):
        if profile == "lemline-compatible":
            grant = node.get("grant")
            if isinstance(grant, str) and grant == "authorization_code":
                add_error(
                    issues,
                    f"{path}/grant",
                    "authorization_code grant is not supported in lemline-compatible profile",
                )
        for key, value in node.items():
            walk_for_grants(value, issues, f"{path}/{key}", profile)
    elif isinstance(node, list):
        for idx, value in enumerate(node):
            walk_for_grants(value, issues, f"{path}/{idx}", profile)


def validate_workflow(data: Any, profile: str) -> list[Issue]:
    issues: list[Issue] = []

    root = ensure_dict(data, issues, "$", "workflow")
    if root is None:
        return issues

    document = ensure_dict(root.get("document"), issues, "$/document", "document")
    if document is not None:
        for required_key in ("dsl", "namespace", "name", "version"):
            if required_key not in document:
                add_error(issues, f"$/document/{required_key}", f"document.{required_key} is required")
        dsl = document.get("dsl")
        if dsl != "1.0.0":
            add_error(issues, "$/document/dsl", "document.dsl must be '1.0.0' for this skill")

    if "do" not in root:
        add_error(issues, "$/do", "workflow.do is required")
        return issues

    use_obj = root.get("use")
    use = use_obj if isinstance(use_obj, dict) else {}
    timeout_names = set(use.get("timeouts", {}).keys()) if isinstance(use.get("timeouts"), dict) else set()
    retry_names = set(use.get("retries", {}).keys()) if isinstance(use.get("retries"), dict) else set()
    error_names = set(use.get("errors", {}).keys()) if isinstance(use.get("errors"), dict) else set()
    auth_names = set(use.get("authentications", {}).keys()) if isinstance(use.get("authentications"), dict) else set()

    if profile == "lemline-compatible":
        if isinstance(use.get("extensions"), (list, dict)):
            add_error(issues, "$/use/extensions", "use.extensions is not supported in lemline-compatible profile")
        evaluate = root.get("evaluate")
        if isinstance(evaluate, dict):
            language = evaluate.get("language")
            if language is not None and language != "jq":
                add_error(issues, "$/evaluate/language", "evaluate.language must be 'jq' in lemline-compatible profile")
    evaluate = root.get("evaluate")
    if isinstance(evaluate, dict):
        mode = evaluate.get("mode")
        if mode is not None and mode not in {"strict", "loose"}:
            add_error(issues, "$/evaluate/mode", "evaluate.mode must be 'strict' or 'loose'")

    validate_task_list(root.get("do"), "$/do", issues, profile, timeout_names, retry_names, error_names, auth_names)

    schedule = root.get("schedule")
    if schedule is not None:
        schedule_obj = ensure_dict(schedule, issues, "$/schedule", "schedule")
        if schedule_obj is not None:
            trigger_keys = [k for k in ("every", "cron", "after", "on") if has_prop(schedule_obj, k)]
            if len(trigger_keys) == 0:
                add_error(issues, "$/schedule", "schedule requires at least one of every, cron, after, on")
            if has_prop(schedule_obj, "every"):
                validate_duration(get_prop(schedule_obj, "every"), issues, "$/schedule/every", "schedule.every")
            if has_prop(schedule_obj, "after"):
                validate_duration(get_prop(schedule_obj, "after"), issues, "$/schedule/after", "schedule.after")
            if has_prop(schedule_obj, "cron") and (
                not isinstance(get_prop(schedule_obj, "cron"), str) or not get_prop(schedule_obj, "cron").strip()
            ):
                add_error(issues, "$/schedule/cron", "schedule.cron must be a non-empty string")
            if has_prop(schedule_obj, "on"):
                validate_event_consumption_strategy(get_prop(schedule_obj, "on"), issues, "$/schedule/on")

    if "timeout" in root:
        validate_timeout_value(root.get("timeout"), timeout_names, issues, "$/timeout")

    retries_obj = use.get("retries")
    if isinstance(retries_obj, dict):
        for name, retry in retries_obj.items():
            if isinstance(retry, dict):
                validate_retry_policy(retry, issues, f"$/use/retries/{name}")
    timeouts_obj = use.get("timeouts")
    if isinstance(timeouts_obj, dict):
        for name, timeout in timeouts_obj.items():
            validate_timeout_value(timeout, set(), issues, f"$/use/timeouts/{name}")

    functions_obj = use.get("functions")
    if isinstance(functions_obj, dict) and functions_obj:
        function_names = set(functions_obj.keys())
        for func_name, func_task in functions_obj.items():
            validate_task(
                func_name,
                func_task,
                function_names,
                issues,
                f"$/use/functions/{func_name}",
                profile,
                timeout_names,
                retry_names,
                error_names,
                auth_names,
            )

    walk_for_grants(root, issues, "$", profile)
    walk_for_placeholders(root, issues, "$")
    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate Serverless Workflow v1.0.0 documents.")
    parser.add_argument("workflow", help="Path to workflow YAML file")
    parser.add_argument(
        "--profile",
        choices=["spec-strict", "lemline-compatible"],
        default="spec-strict",
        help="Validation profile",
    )
    args = parser.parse_args()

    try:
        data = load_yaml(args.workflow)
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR $: failed to parse YAML: {exc}")
        return 2

    issues = validate_workflow(data, args.profile)
    for issue in issues:
        print(f"{issue.severity} {issue.path}: {issue.message}")

    error_count = sum(1 for i in issues if i.severity == "ERROR")
    warn_count = sum(1 for i in issues if i.severity == "WARN")
    if error_count == 0:
        print(
            f"OK: {args.workflow} is valid for profile '{args.profile}'"
            f" ({warn_count} warning{'s' if warn_count != 1 else ''})"
        )
        return 0
    print(f"FAILED: {error_count} error(s), {warn_count} warning(s)")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
