# Review Checklist

Every review artifact and implementer self-review must include this checklist. An `APPROVED` verdict with any applicable unchecked item is invalid.

Status markers:

- `[x]` verified / satisfied
- `[ ]` missing / unsatisfied and must be raised as a finding
- `[~]` not applicable, with a reason

## 1. Code Walk

- [ ] I read the real code on disk, not just summaries or prior review text.
- [ ] I verified the actual call/data paths involved.
- [ ] I checked sibling/shared/helper paths outside the named files.
- [ ] I ran at least one pattern-class scan appropriate to the change.

## 2. Measurement Correctness

- [ ] Timestamp handling is explicit and tested where relevant.
- [ ] Calibration, detection, fit, and unit conversion fail loud on invalid inputs.
- [ ] No result can display a plausible mph from insufficient or ambiguous evidence.
- [ ] Camera/device assumptions are documented and backed by measured evidence when required.

## 3. Tests Ship With Code

- [ ] Unit/golden tests exist where applicable.
- [ ] Regression coverage exists for bug fixes.
- [ ] Error-path coverage exists.
- [ ] Edge/boundary coverage exists.
- [ ] Real pipeline/integration coverage exists for data-driven changes.
- [ ] Device/on-camera coverage is recorded when JVM tests cannot prove the behavior.
- [ ] No application logic is mock-replaced.

## 4. Docs Ship With Code

- [ ] Public APIs and non-obvious algorithms have KDoc or equivalent comments.
- [ ] `docs/HOW_THE_APPLICATION_WORKS.md` is updated when behavior changes.
- [ ] `docs/DATA_FLOW.md` is updated when pipeline flow changes.
- [ ] `docs/FUNCTIONAL_TEST_REGISTRY.md` is updated.
- [ ] Device evidence docs are updated only with measured evidence.

## 5. Security / Privacy

- [ ] I checked whether this touches camera, media import, filesystem, logs, permissions, or user data.
- [ ] Relevant `docs/SECURITY_CHECKLIST.md` items were verified.
- [ ] Secrets, local config, media, APKs, and keystores are not committed.

## 6. Verdict Integrity

- [ ] Every unchecked applicable item above is raised as a finding.
- [ ] If I am sending `APPROVED`, every applicable item above is checked.
