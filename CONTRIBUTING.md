# Contributing Guide

Thank you for contributing to this project. This file is the sole contribution guide for the repository and defines the automated contract enforced by the pull request policy, CI checks, and the `master` branch ruleset. It also records maintainer-reviewed expectations that apply when relevant.

## Code of Conduct

This project follows a [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold it.

## Reporting a Bug

**Before creating a report:**
- Check if the bug has already been reported
- Make sure you're on the latest version
- Check existing discussions

**When creating a report:**
- Use the [Bug Report](.github/ISSUE_TEMPLATE/bug_report.md) template
- Include steps to reproduce
- Provide environment details (OS, JDK version, etc.)
- Add logs or screenshots if possible

## Suggesting a Feature

- Use the [Feature Request](.github/ISSUE_TEMPLATE/feature_request.md) template
- Describe the problem you want to solve
- Explain why this feature would be useful
- If possible, suggest an implementation approach

## Submitting a Pull Request

Every pull request must satisfy the repository contract below before it can merge.

### Fork workflow (mandatory)

The primary repository is [Graphiks-org/Kalligraphie](https://github.com/Graphiks-org/Kalligraphie). **You must work from your own fork** of this repository: do not create contribution branches directly in the primary repository. Open every contribution pull request from a branch in your fork, with [Graphiks-org/Kalligraphie](https://github.com/Graphiks-org/Kalligraphie) as its target repository.

### Machine-enforced blocking rules

These are the checks that must pass before merge. They are enforced by the PR policy, CI, or the protected `master` branch ruleset.

- Open the PR from a branch prefixed with `feat/`, `fix/`, or `chore/`.
- Keep branch ancestry aligned with the latest `master`; the final merge into `master` is squash-only.
- Use Conventional Commits for the PR title and every non-merge commit subject: `<type>(<scope>): <description>`.
- Allowed PR and commit types are `feat`, `fix`, `build`, `chore`, `ci`, `docs`, `perf`, `refactor`, `test`, and `style`.
- Use one of the current-project scopes listed under [Conventional Commits](#conventional-commits). The machine-readable list is defined in [.github/contributing-policy.toml](.github/contributing-policy.toml).
- Use the exact [PR template](.github/PULL_REQUEST_TEMPLATE.md) headings: `Description`, `Type of Change`, `Checklist`, `Screenshots (if applicable)`, and `Additional Notes`.
- Select exactly one change type checkbox in the PR body.
- Record the changelog decision explicitly in the PR checklist:
  - check `CHANGELOG.md has been updated`, or
  - check `No changelog update needed:` and provide a justification.
- Record the documentation decision explicitly by checking or leaving unchecked `Documentation updated if needed`.
- The blocking GitHub check is `PR policy`.
- The `master` branch ruleset requires no direct pushes, one approval, resolved review conversations, branches up to date with `master`, and squash-only merges.
- Maintainer-only exceptions must stay limited to the bypass configuration of the GitHub repository ruleset.

Repository settings automatically delete head branches after successful merges.

### Maintainer-reviewed expectations

These items are reviewed by maintainers when applicable; they are not automatically enforced by CI or the branch ruleset.

- Keep commits atomic when practical.
- Run local verification before requesting review: `./gradlew check`.
- Reference the related issue in the PR description when relevant.
- Add screenshots when relevant.
- Keep the `Screenshots (if applicable)` and `Additional Notes` sections when relevant.

### Submission checklist

Before submitting a PR, make sure:

**Blocking checks**

- [ ] Title follows Conventional Commits format
- [ ] Commit subjects follow Conventional Commits format
- [ ] PR body uses the required template headings and exactly one change type
- [ ] `CHANGELOG.md` is updated, or the PR body justifies why no changelog update is needed
- [ ] Documentation decision is recorded in the PR body
- [ ] Branch is based on the latest `master`
- [ ] Branch uses a permitted prefix: `feat/`, `fix/`, or `chore/`
- [ ] The PR targets a branch that satisfies the protected `master` ruleset

**Maintainer-reviewed expectations**

- [ ] Tests pass locally (`./gradlew check`)
- [ ] Commits are atomic when practical
- [ ] The PR description references the related issue when relevant
- [ ] Screenshots are included when relevant
- [ ] Additional notes are included when relevant

### Local Build

```bash
# Standard Gradle verification
./gradlew check

# All tests
./gradlew allTests

# Generate and embed API docs into MkDocs
./gradlew :docs:embedDokkaIntoMkDocs
```

### Conventional Commits

This project uses [Conventional Commits](https://www.conventionalcommits.org/).

**Format:** `<type>(<scope>): <description>`

**Allowed types:**

| Type       | Usage                                              |
|-----------|----------------------------------------------------|
| `feat`    | New feature                                        |
| `fix`     | Bug fix                                            |
| `build`   | Build system or dependencies                       |
| `chore`   | Maintenance, tooling, dependencies                 |
| `ci`      | CI/CD configuration                                |
| `docs`    | Documentation changes                              |
| `perf`    | Performance improvement                            |
| `refactor`| Code refactoring (no behavior change)              |
| `test`    | Adding or fixing tests                             |
| `style`   | Code style (formatting, imports ordering)          |

**Scopes:**

Choose the narrowest applicable scope from the current project structure:

| Scope | Responsibility and location |
|-------|-----------------------------|
| `kalligraphie` | Consumer facade in `:kalligraphie` |
| `api` | Public contracts in `:kalligraphie:api` |
| `unicode` | Unicode analysis in `:kalligraphie:unicode` |
| `font` | Font management across the `kalligraphie/font/` module family |
| `font-core` | Font catalogues and instances in `:kalligraphie:font:core` |
| `sfnt` | SFNT/OpenType parsing in `:kalligraphie:font:sfnt` |
| `scaler` | Font scaling and geometry in `:kalligraphie:font:scaler` |
| `glyph` | Glyph representation materialization in `:kalligraphie:font:glyph` |
| `shaping` | Text shaping backends in `:kalligraphie:shaping` |
| `layout` | Text layout and editing geometry in `:kalligraphie:layout` |
| `platform` | Platform integration across the `kalligraphie/platform/` module family |
| `apple` | Apple font integration in `:kalligraphie:platform:apple` |
| `buildSrc` | Gradle conventions in `buildSrc/` |
| `ci` | Automated repository checks in `.github/workflows/` and `.github/scripts/` |
| `conformance` | Portable conformance contract and corpus in `:kalligraphie:conformance` |
| `docs` | Documentation in `docs/` and repository documentation files |
| `release` | Release workflow, publication and versioning |

Use `font` or `platform` for a change spanning their respective module families.
Keep this table and `allowed_scopes` in [.github/contributing-policy.toml](.github/contributing-policy.toml)
synchronized whenever an implemented module is introduced, renamed or removed.
Only scopes corresponding to existing repository responsibilities are permitted.

**Examples:**
```
feat(font): add glyph parser
feat(apple): add certified CoreText font access
fix(layout): preserve caret geometry across line wrapping
fix(buildSrc): resolve AGP compatibility issue
docs: update README with new badges
```

### Git Workflow

**Branches:**
- `master` — release branch (protected)
- `feat/*` — new features
- `fix/*` — bug fixes
- `chore/*` — maintenance, tooling

**Rules:**
- No direct commits to `master`
- Branches must be based on the latest `master` before PR
- Commits should be atomic (one change per commit)

### Review Process

1. **Create a Pull Request**
   - Use the [PR template](.github/PULL_REQUEST_TEMPLATE.md)
   - Title in Conventional Commits format
   - Reference related issues
   - Select exactly one change type
   - Declare the changelog decision for `CHANGELOG.md`
   - Record the documentation decision explicitly

2. **Review**
   - At least 1 approval is required
   - `PR policy` must pass as the blocking check
   - All review conversations must be resolved
   - The branch must be up to date with `master`

3. **Merge**
   - Strategy: squash merge only
   - Squash title must keep Conventional Commits format
   - Delete branch after merge

### Versioning

This project follows [Semantic Versioning](https://semver.org/).

- `MAJOR` — breaking change
- `MINOR` — backward-compatible feature
- `PATCH` — backward-compatible fix

SNAPSHOT versions (`1.0.0-SNAPSHOT`) are used during active development.
Release versions are published via the release workflow (`releaseVersion` property).

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
