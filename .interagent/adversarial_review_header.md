# ADVERSARIAL REVIEW REQUIRED

PER .interagent/docs/INTERAGENT_PROTOCOL.md (READ IT)

You must perform a fresh adversarial review using **staged context**:

1. **Protocol first:** read the consolidated interagent review protocol enough to know the required role, mailbox, progress-log, checklist, and verdict rules.
2. **Code-first pass before narrative bias:** read only the Review Seed section, then run the seed's `Diff locator` command and any listed file-list/diff-stat commands before relying on the sender's explanation. Do not read the sender's claims, prior-findings summary, or implementation narrative until after this first pass.
3. **Independent failure model:** from the code/diff, list the bug classes you expect, run pattern-class scans, and form your own theory of what could break.
4. **Context pass:** then read the full request/response/progress log and relevant domain docs. Use that context as a map and business contract, not as ground truth.
5. **Synthesis pass:** verify prior claims against real code, expand scope where the bug class points, audit tests/docs/security/checklist requirements, and write findings with file:line references.

Do not confirm prior summaries, prior approvals, or the sender's framing. If you skip the code-first pass or treat the narrative as truth, the review is invalid.
