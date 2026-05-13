---
name: enrich-us
description: Analyze and enhance user stories with complete, implementation-ready technical detail from direct ticket input or Notion. Project-level override of the upstream lidr-specboot skill — swaps Jira for Notion since this project tracks backlog in a Notion page, not Jira.
author: LIDR.co (upstream) · adapted for MyFinanceView
version: 1.0.0-notion
---
# enrich-us Skill — MyFinanceView (Notion-flavored)

This is a **project-level override** of the upstream `enrich-us` skill. The upstream version assumes Jira; this project uses **Notion** as the backlog. Behavior is otherwise identical to upstream.

Use it when this workflow is required in the project.

## Instructions

Please analyze and enrich the ticket: $ARGUMENTS.

Follow these steps:

1. Determine the ticket input source:
   - **Direct input mode (default when ticket text is provided):** Use the ticket content shared by the user in the prompt/chat.
   - **Notion mode (optional):** If the user provides a Notion page URL/ID (auto-detected by `notion.so/`, `notion.site/`, or a UUID), or asks to use Notion (including references like "the one in progress"), use the Notion MCP tool `mcp__claude_ai_Notion__notion-fetch` to fetch the page details.
2. Act as a product expert with technical knowledge.
3. Understand the problem described in the ticket.
4. Decide whether or not the User Story is completely detailed according to product best practices. Validate that it includes:
   - Full functionality description
   - Comprehensive list of fields to update
   - Required endpoints structure and URLs
   - Files/modules to modify according to architecture and best practices
   - Definition of done (implementation and delivery steps)
   - Documentation and unit test updates
   - Non-functional requirements (security, performance, observability, etc.)
5. If the story lacks enough technical detail for autonomous implementation, provide an improved version that is clearer, more specific, and concise, aligned with step 4. Use project technical context from `@SPEC.md`, `@docs/`, and `@plans/`. Return the result in markdown.
6. Output format must always include:
   - `## Original`
   - `## Enhanced`
7. Notion write-back is optional and only applies in Notion mode:
   - Update the Notion page by appending the enhanced content after the original content, using `mcp__claude_ai_Notion__notion-update-page` with `update_content`. Use clear `h2` sections `[original]` and `[enhanced]` and readable formatting (lists/code snippets when useful). Append, never replace — preserve the original text.
   - Do not write back without explicit user confirmation, even when in Notion mode.

## Notes

- Do not require Notion when the user already provided full ticket content directly.
- If input is ambiguous (for example, user gives a short reference without content), ask whether to resolve via Notion or request the full ticket text.
- The project's Notion backlog page is: https://www.notion.so/35d8c9b709f081c08d62f7257ce3db57 — épicas and TASK-* cards live here.
- This project does **not** use Jira. Ignore Jira references from the upstream skill description.
