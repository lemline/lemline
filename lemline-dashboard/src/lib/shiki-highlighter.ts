import { createHighlighterCore } from "@shikijs/core";
import { createJavaScriptRegexEngine } from "@shikijs/engine-javascript";
import json from "@shikijs/langs/json";
import yaml from "@shikijs/langs/yaml";
import githubDark from "@shikijs/themes/github-dark";
import githubLight from "@shikijs/themes/github-light";

type DefinitionTheme = "github-dark" | "github-light";

let highlighterPromise: ReturnType<typeof createHighlighterCore> | undefined;

function getHighlighter() {
  if (!highlighterPromise) {
    highlighterPromise = createHighlighterCore({
      engine: createJavaScriptRegexEngine(),
      langs: [...yaml, ...json],
      themes: [githubDark, githubLight],
    });
  }
  return highlighterPromise;
}

export async function highlightYaml(code: string, theme: DefinitionTheme): Promise<string> {
  const highlighter = await getHighlighter();
  return highlighter.codeToHtml(code, { lang: "yaml", theme });
}

export async function highlightJson(code: string, theme: DefinitionTheme): Promise<string> {
  const highlighter = await getHighlighter();
  return highlighter.codeToHtml(code, { lang: "json", theme });
}
