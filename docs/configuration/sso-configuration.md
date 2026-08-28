# SSO Configuration

Morphe PDF supports single sign-on via OAuth2/OIDC (Google, GitHub, Keycloak, or any
generic OpenID Connect provider) and SAML2. Settings live under `security.oauth2` and
`security.saml2` in `settings.yml` / `application.properties`, or the matching
`SECURITY_OAUTH2_*` / `SECURITY_SAML2_*` environment variables. They can also be managed
from **Admin Settings > SSO / OAuth2** in the app UI.

Set `security.loginMethod` to `all`, `oauth2`, or `saml2` to control which login options
are shown alongside standard username/password.

## OAuth2 / OIDC

```yaml
security:
  oauth2:
    enabled: true
    provider: google        # google | github | keycloak | <any other name for a generic provider>
    issuer: ""               # required for keycloak/generic; OIDC discovery URL
    clientId: ""
    clientSecret: ""
    scopes: email, profile   # comma-separated
    useAsUsername: email     # claim/attribute used as the username
    autoCreateUser: true
    blockRegistration: false
```

Provider-specific notes:

- **Google** — create an OAuth Client ID in Google Cloud Console. Default scopes:
  `email, profile`. `useAsUsername` accepts `email`, `name`, `given_name`, `family_name`.
- **GitHub** — create an OAuth App in GitHub Developer Settings. Default scope:
  `read:user`. `useAsUsername` accepts `email`, `login`, `name`.
- **Keycloak** — set `issuer` to the realm's OIDC discovery URL, e.g.
  `https://keycloak.example.com/realms/myrealm`. `useAsUsername` accepts
  `preferred_username` (default), `email`, `name`, `given_name`, `family_name`.
- **Generic OIDC** (Azure AD, Okta, etc.) — set `provider` to any name and `issuer` to a
  URL that serves `/.well-known/openid-configuration`.

## SAML2

```yaml
security:
  saml2:
    enabled: true
    provider: okta
    registrationId: morphe          # Service Provider (SP) app name
    idpMetadataUri: https://idp.example.com/metadata     # or classpath:idp.xml
    idpSingleLoginUrl: https://idp.example.com/sso
    idpSingleLogoutUrl: https://idp.example.com/slo
    idpIssuer: ""
    idpCert: classpath:idp.cert
    privateKey: classpath:saml-private-key.key
    spCert: classpath:saml-public-cert.crt
    autoCreateUser: true
    blockRegistration: false
```

`idpMetadataUri` accepts either an HTTP(S) URL or a `classpath:`-prefixed path bundled
with the app. Certificate/key fields accept a `classpath:` resource or a filesystem path.

## Troubleshooting

Set `security.oauth2.debugLogging: true` to log the full set of ID token / UserInfo
claims at startup — useful when a provider isn't returning the claim named in
`useAsUsername` (e.g. ADFS omitting `email`). This logs PII (sub, email, name); leave it
off in production and re-disable it once you're done.

See [`ApplicationProperties.java`](../../app/common/src/main/java/stirling/software/common/model/ApplicationProperties.java)
for the full set of fields, and open an issue at
[github.com/binesh-balan/Morphe/issues](https://github.com/binesh-balan/Morphe/issues) if
something here doesn't match the running app.
