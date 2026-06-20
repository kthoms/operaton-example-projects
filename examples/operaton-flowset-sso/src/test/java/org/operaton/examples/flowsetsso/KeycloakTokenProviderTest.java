package org.operaton.examples.flowsetsso;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KeycloakTokenProviderTest {

    @Test
    void extractsAccessTokenFromJson() {
        String json = "{\"access_token\":\"abc.def.ghi\",\"expires_in\":300}";
        assertThat(KeycloakTokenProvider.parseAccessToken(json)).isEqualTo("abc.def.ghi");
    }
}
