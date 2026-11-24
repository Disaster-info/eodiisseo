package com.disasterinfo.eodiisseo.ai.chat;

import com.disasterinfo.eodiisseo.ai.chat.controller.ChatController;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatRequest;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.service.OwnedRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    void unavailableRateLimitStorageFailsClosedWithoutStartingGuidance() {
        var service = mock(ChatService.class);
        var limiter = mock(OwnedRateLimiter.class);
        var principal = new MemberPrincipal(UUID.randomUUID(), UUID.randomUUID());
        when(limiter.allowAiGuidance(principal.memberId()))
                .thenThrow(new DataAccessResourceFailureException("sensitive database detail"));
        var beans = new StaticListableBeanFactory(Map.of("limiter", limiter));
        var controller = new ChatController(service, beans.getBeanProvider(OwnedRateLimiter.class));

        assertThatThrownBy(() -> controller.requestGuidance(principal, new ChatRequest("지진 행동요령")))
                .isInstanceOfSatisfying(ChatException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(ChatException.Reason.DEPENDENCY_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("sensitive database detail");
                });
        verifyNoInteractions(service);
    }
}
