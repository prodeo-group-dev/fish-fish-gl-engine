# Moved

The Terraform project that used to live here (`infra/terraform/`) moved to
its own repo, [`fish-infrastructure`](https://github.com/prodeo-group-dev/fish-infrastructure),
2026-09-17. See `FiSH/docs/Platform_Infrastructure_Extraction_Design.md` for
why — it provisioned infrastructure for every FiSH service, not just GL, and
never belonged bundled with this repo's own application code.

Nothing about the AWS resources themselves changed. `GL/CLAUDE.md`'s AWS
guidance now points at the new repo.
