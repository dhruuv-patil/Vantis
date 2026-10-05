# Vantis — Production Secret Rotation & Management Guide

> [!IMPORTANT]
> **Operator Action Required**: Following the security audit, any plaintext/live credentials previously present in local or environment configuration files (`.env`, `backend/.env`, `ai-service/.env`) must be revoked at their external providers and regenerated. Real secrets must **never** be committed to version control.

---

## 1. Scope of Credentials

The affected credential categories requiring immediate rotation:
1. **Google Gemini API Key**: Previously configured in `.env` / `ai-service/.env`.
2. **Gmail SMTP App Password**: Previously configured in `.env` (`SMTP_PASSWORD`).
3. **JWT Signing Secret (`JWT_SECRET`)**: Used for signing backend authentication tokens.
4. **Integration Encryption Key (`ENCRYPTION_KEY`)**: 256-bit AES-GCM symmetric key encrypting integration credentials at rest.
5. **AI Service Token (`AI_SERVICE_TOKEN`)**: Shared secret authenticating Spring Boot backend requests to the Python AI service.

---

## 2. Operator Rotation Checklist

### Step 1: Revoke & Regenerate Google Gemini API Key
- Navigate to the [Google AI Studio API Keys Console](https://aistudio.google.com/apikey).
- Locate the previously used API key and delete / revoke it.
- Create a new API key.
- Restrict key permissions if applicable in Google Cloud Console.
- Configure `GEMINI_API_KEY=<new-key>` exclusively in the deployment environment / secret manager for the AI service.

### Step 2: Revoke & Regenerate Gmail SMTP App Password
- Log in to the Google Account used for SMTP delivery (`SMTP_USERNAME`).
- Go to **Manage your Google Account** > **Security** > **2-Step Verification** > **App passwords**.
- Delete the revoked app password named for Vantis.
- Generate a new 16-character App Password.
- Configure `SMTP_PASSWORD=<new-app-password>` in the backend deployment environment.

### Step 3: Rotate JWT Signing Secret (`JWT_SECRET`)
- **Generation**: Generate a cryptographically random secret with at least 256 bits (>= 32 bytes) of entropy:
  ```bash
  openssl rand -base64 48
  ```
- **Requirements**:
  - Must decode to or contain at least 32 bytes.
  - Must not match any committed denylisted placeholders (`change-me`, `dev-secret`, etc.), otherwise `AuthPropertiesValidator` will fail startup fast.
- **Operational Impact**:
  - Rotating `JWT_SECRET` immediately invalidates all active JWT access tokens.
  - Clients holding valid, unexpired refresh tokens in HTTP-only cookies will automatically exchange them for new access tokens on their next API request without user disruption.
  - Users with expired or invalid refresh tokens will simply be prompted to log in again.

### Step 4: Rotate AI Service Token (`AI_SERVICE_TOKEN`)
- **Generation**: Generate a cryptographically strong random token:
  ```bash
  openssl rand -hex 32
  ```
- **Requirements**:
  - Configure the **exact same token** in both services:
    - Backend: `AI_SERVICE_TOKEN`
    - AI Service: `AI_SERVICE_TOKEN`
  - The backend presents this token via the `X-Service-Token` HTTP header on all `/ai/*` requests.
  - The AI service validates the token in constant time (`hmac.compare_digest`) via `require_service_token`.
  - **Never** expose `AI_SERVICE_TOKEN` to frontend bundles (`NEXT_PUBLIC_*`).
  - The AI service is deployed on internal networks only (container port 8100 is not exposed to the public internet).

### Step 5: Rotate Integration Encryption Key (`ENCRYPTION_KEY`)
> [!CAUTION]
> **DO NOT BLINDLY ROTATE `ENCRYPTION_KEY` IF ENCRYPTED INTEGRATIONS EXIST IN THE DATABASE!**
> Stored integration credentials (e.g., Slack, GitHub, Jira, email tokens) are encrypted with AES-256-GCM. Changing the key without migrating the database will cause decryption failures (`AEADBadTagException`) and permanently break active customer integrations.

#### Understanding the Encryption Implementation
- Algorithm: AES-256-GCM (`AES/GCM/NoPadding`) implemented in `CredentialCipher.java`.
- Key length: Exactly 32 bytes (256 bits), Base64-encoded.
- Nonce/IV: 12-byte cryptographically random IV generated per encryption.
- Authentication Tag: 128 bits (16 bytes) appended to ciphertext.
- Ciphertext blob format: `Base64( IV (12 bytes) || Ciphertext || Tag (16 bytes) )`.

#### Safe Key Migration Procedure
If existing integrations exist in the database:
1. **Generate the new key**:
   ```bash
   NEW_KEY=$(openssl rand -base64 32)
   ```
2. **Execute migration re-encryption**:
   Run an administrative migration script or offline task with access to both `OLD_KEY` and `NEW_KEY`:
   - Initialize `legacyCipher` with `OLD_KEY`.
   - Initialize `targetCipher` with `NEW_KEY`.
   - Query all rows from `integrations` table with non-null `encrypted_credentials`.
   - In an atomic database transaction:
     ```
     For each row:
       1. decrypted_json = legacyCipher.decrypt(row.encrypted_credentials)
       2. re_encrypted_blob = targetCipher.encrypt(decrypted_json)
       3. UPDATE integrations SET encrypted_credentials = re_encrypted_blob WHERE id = row.id
     ```
3. **Deploy the new key**:
   - Update `ENCRYPTION_KEY` in the backend environment to `NEW_KEY`.
   - Restart the backend service.
   - Verify health checks: `GET /health/ready` returns `200 OK`.
   - Test an existing integration to confirm valid decryption.
4. **Retire the old key**:
   - Safely remove the old key from secret store / environment backups.

*Note: In fresh deployments or environments where no customer integrations have been stored, the new key can be applied directly without data migration.*

---

## 3. Production Environment Variables Reference

| Variable | Target Service | Secret? | Description / Required Value |
| :--- | :--- | :---: | :--- |
| `JWT_SECRET` | Backend | **Yes** | `>= 256-bit` random string (`openssl rand -base64 48`) |
| `ENCRYPTION_KEY` | Backend | **Yes** | Base64-encoded 32-byte key (`openssl rand -base64 32`) |
| `AI_SERVICE_TOKEN` | Backend & AI Service | **Yes** | Shared secret between services (`openssl rand -hex 32`) |
| `GEMINI_API_KEY` | AI Service | **Yes** | Google AI Studio API key |
| `OMNIROUTE_API_KEY` | AI Service | **Yes** | Optional OmniRoute / OpenAI API key |
| `SMTP_PASSWORD` | Backend | **Yes** | Gmail App Password or SMTP relay password |
| `SPRING_DATASOURCE_PASSWORD` | Backend | **Yes** | PostgreSQL production database password |
| `POSTGRES_PASSWORD` | PostgreSQL | **Yes** | PostgreSQL root/app password |
| `AI_SERVICE_URL` | Backend | No | Address of AI service (`http://ai-service:8100`) |
| `PUBLIC_BASE_URL` | Backend | No | Public backend URL (e.g. `https://api.vantis.run`) |
| `FRONTEND_PUBLIC_URL` | Backend | No | Public frontend URL (e.g. `https://vantis.run`) |
| `REFRESH_COOKIE_SECURE` | Backend | No | `true` in production (HTTPS-only) |
| `REFRESH_COOKIE_SAME_SITE` | Backend | No | `None` in production |
| `NEXT_PUBLIC_API_URL` | Frontend | No | Public API URL accessible from browser |

---

## 4. Verification Checklist for Deployment

- [ ] All `.env` and `*.key` / `*.pem` files are excluded by `.gitignore`.
- [ ] No plaintext secrets exist in any repository templates or tracked files.
- [ ] Backend starts up successfully and passes `AuthPropertiesValidator` and `CryptoProperties` checks.
- [ ] AI service requires and validates `X-Service-Token`.
- [ ] Frontend bundle verified to contain zero backend secrets or provider keys.
