# Security Policy

## Reporting a Vulnerability

Please report suspected vulnerabilities privately. Do not open a public
GitHub issue or pull request.

Email **admin@tsicoop.org** with:

- A description of the issue and its impact.
- Steps to reproduce, or a PoC if available.
- The affected version/commit.

We aim to acknowledge reports within a reasonable timeframe and will work
with you on a disclosure timeline once the issue is confirmed.

## Supported Versions

Security fixes are applied to the latest release. There is no long-term
support for older versions; upgrading to the latest release is the
recommended remediation path.

## Deployment checklist

Before exposing an instance beyond localhost:

- Set `TSI_NEXUS_JWT_SECRET` (min 32 chars) and `TSI_NEXUS_BOOTSTRAP_TOKEN`
  (see `.env.example`). The server refuses to start without the former; the
  first-run setup endpoint refuses every request without the latter.
- Change `POSTGRES_PASSWD` and `POSTGRES_RO_PASSWD` from their dev defaults.
- Set `TSI_NEXUS_ENV=production` and serve over HTTPS so the console
  session cookie carries the `Secure` flag.
