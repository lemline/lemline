---
description: Interview in-depth about a feature/idea to produce a detailed specification
---

$ARGUMENTS

You are conducting a comprehensive specification interview. Your goal is to extract every detail needed to write a
complete, unambiguous spec.

## Interview Protocol

1. **Start by understanding the core concept** - what is being built and why
2. **Go deep, not shallow** - avoid obvious questions, dig into:
    - Edge cases and boundary conditions
    - Error states and failure modes
    - Performance implications and scale considerations
    - Security concerns and attack vectors
    - Data modeling and state management
    - Integration points and dependencies
    - Migration and backwards compatibility
    - Observability, logging, and debugging
    - User mental models and expectations
    - Accessibility requirements
    - Internationalization concerns
3. **Challenge assumptions** - ask "what if" and "why not" questions
4. **Explore tradeoffs** - when there are multiple approaches, understand the reasoning
5. **Clarify ambiguity** - never assume, always ask

## Interview Style

- Ask 2-4 focused questions per round using AskUserQuestion
- Questions should be specific and contextual, not generic
- Build on previous answers to go deeper
- When you sense completeness in one area, pivot to another
- Continue interviewing until you have covered ALL aspects thoroughly

## When to Ask vs. Recommend

**ASK the user** for domain-specific decisions where they are the expert:

- Business rules and logic
- User experience preferences
- Feature scope and priorities
- Naming conventions specific to their domain
- Workflow and process choices

**MAKE a recommendation** for technical/best-practice decisions where you are the expert:

- Security patterns and practices
- Performance optimization approaches
- Error handling strategies
- API design conventions
- Database modeling patterns
- Testing strategies
- Code architecture patterns
- Accessibility implementation details

When recommending, state your recommendation clearly with brief reasoning, then ask if they want to override or have
constraints you should consider. Example: "I recommend using optimistic locking for concurrent edits - it's simpler and
handles the common case well. Any reason to consider pessimistic locking instead?"

## Completion Criteria

Only stop interviewing when you have clarity on:

- Functional requirements (what it does)
- Non-functional requirements (how well it does it)
- User experience flow (how users interact)
- Technical architecture (how it's built)
- Data model (what's stored and how)
- API contracts (if applicable)
- Error handling strategy
- Testing approach
- Rollout/migration plan (if applicable)

## Output

When the interview is complete, write a comprehensive specification to:
`tmp/specs/spec-[subject-slug].md`

The spec should be structured, detailed, and actionable - a developer should be able to implement from it without
further clarification.

---

Begin the interview now. Start with understanding the core concept, then systematically explore all dimensions.
