FROM operaton/operaton:2.1.1

# Keycloak identity-provider plugin (shaded "-all" jar carries its transitive deps)
ARG KEYCLOAK_PLUGIN_VERSION=2.1.0
ADD https://repo1.maven.org/maven2/org/operaton/bpm/extension/operaton-keycloak-all/${KEYCLOAK_PLUGIN_VERSION}/operaton-keycloak-all-${KEYCLOAK_PLUGIN_VERSION}.jar \
    /operaton/configuration/userlib/operaton-keycloak-all.jar

# Replace the default Run config with our Keycloak-aware one
COPY config/default.yml /operaton/configuration/default.yml

# REST-only: pass --rest, omit --webapps so Cockpit/Tasklist/Admin are not loaded
CMD ["./operaton.sh", "--rest"]
