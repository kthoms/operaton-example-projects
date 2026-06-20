#!/usr/bin/env bash
# Seeds the 'operaton' realm. Idempotent: ignores "already exists" (HTTP 409).
set -euo pipefail
KC=/opt/keycloak/bin/kcadm.sh
SERVER="${KC_SERVER:-http://keycloak:8080}"

create() { "$KC" "$@" 2>/dev/null || true; }   # tolerate 409 conflicts

"$KC" config credentials --server "$SERVER" --realm master \
  --user "${KC_ADMIN:-admin}" --password "${KC_ADMIN_PASSWORD:-admin}"

create create realms -s realm=operaton -s enabled=true -s loginTheme=operaton

# --- Clients ---
create create clients -r operaton -s clientId=oauth2-proxy -s enabled=true \
  -s publicClient=false -s standardFlowEnabled=true -s secret=oauth2-proxy-secret \
  -s 'redirectUris=["https://localhost:8080/oauth2/callback"]' -s 'webOrigins=["+"]'

create create clients -r operaton -s clientId=flowset-control -s enabled=true \
  -s publicClient=false -s standardFlowEnabled=true -s secret=flowset-control-secret \
  -s 'redirectUris=["https://localhost:8080/control/*"]' -s 'webOrigins=["+"]'

create create clients -r operaton -s clientId=operaton-identity-service -s enabled=true \
  -s publicClient=false -s serviceAccountsEnabled=true -s standardFlowEnabled=false \
  -s secret=operaton-identity-secret

# Grant the identity-service service account read access to users/groups
SA=service-account-operaton-identity-service
for role in view-users query-users query-groups; do
  create add-roles -r operaton --uusername "$SA" --cclientid realm-management --rolename "$role"
done

# --- Groups ---
for g in employees underwriters operaton-admin; do
  create create groups -r operaton -s name="$g"
done

# --- Users (username:password:group) ---
seed_user() {
  local u="$1" p="$2" grp="$3"
  create create users -r operaton -s username="$u" -s enabled=true \
    -s email="$u@example.com" -s emailVerified=true -s firstName="$u" -s lastName=User
  "$KC" set-password -r operaton --username "$u" --new-password "$p" 2>/dev/null || true
  local uid gid
  uid=$("$KC" get users -r operaton -q username="$u" --fields id --format csv --noquotes | head -1)
  gid=$("$KC" get groups -r operaton -q search="$grp" --fields id --format csv --noquotes | head -1)
  "$KC" update "users/$uid/groups/$gid" -r operaton -s realm=operaton \
    -s userId="$uid" -s groupId="$gid" -n 2>/dev/null || true
}
seed_user alice  alice  employees
seed_user eve    eve    underwriters
seed_user admin  admin  operaton-admin
seed_user worker worker operaton-admin

echo "Realm 'operaton' seeded."
