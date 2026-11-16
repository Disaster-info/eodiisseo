package com.disasterinfo.eodiisseo.notification.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live OAuth-only check. Requires FCM_SERVICE_ACCOUNT_PATH and Google network access.
 * It neither sends an FCM message nor prints/returns the OAuth token or private key.
 * Run only with EODIISSEO_FCM_LIVE_OAUTH_TEST=true explicitly set in the test shell.
 */
class FcmLiveOAuthSmokeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "EODIISSEO_FCM_LIVE_OAUTH_TEST", matches = "true")
    void serviceAccountCanFetchGoogleAccessTokenWithoutSendingPush() {
        String path = System.getenv("FCM_SERVICE_ACCOUNT_PATH");
        assertTrue(path != null && !path.isBlank(), "FCM_SERVICE_ACCOUNT_PATH must be configured");
        var tokens = new ServiceAccountFcmTokens(path);
        assertTrue(tokens.isConfigured(), "Firebase service account is not configured correctly");
        String accessToken = tokens.accessToken();
        assertTrue(accessToken != null && !accessToken.isBlank(),
                "Firebase OAuth token request failed; check credentials, connectivity and service account permissions");
    }
}
